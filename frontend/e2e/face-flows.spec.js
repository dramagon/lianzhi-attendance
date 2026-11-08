import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const envelope = (data) => ({ code: 'OK', message: '操作成功', data })
async function login(page) {
  await page.goto('/login')
  await page.getByRole('button', { name: '员工', exact: true }).click()
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}
async function collectionCamera(page) {
  await page.addInitScript(() => {
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
      value: async () => {
        const canvas = document.createElement('canvas')
        canvas.width = canvas.height = 240
        canvas.getContext('2d').fillRect(0, 0, 240, 240)
        return canvas.captureStream(10)
      },
    })
  })
}
const simulatedStatus = () => ({
  available: true,
  detection: true,
  comparison: true,
  liveness: true,
  provider: 'UI_CONTRACT_TEST_ONLY',
  message: '模拟接口，仅验证交互',
  enrolled: false,
  revision: 0,
  deletionAvailable: false,
  punchRequired: false,
})
async function openCollection(page) {
  await page
    .getByRole('checkbox', { name: '我同意本次采集、服务端识别和加密特征处理', exact: true })
    .check()
  await page.getByRole('button', { name: '开启采集摄像头', exact: true }).click()
  await expect
    .poll(() =>
      page.getByLabel('人脸采集画面', { exact: true }).evaluate((video) => video.videoWidth),
    )
    .toBeGreaterThan(0)
}

test('真实未配置服务关闭登记采集，不把本机预览当识别', async ({ page }) => {
  await login(page)
  await page.goto('/attendance/face')
  await expect(page.getByRole('button', { name: '采集并登记人脸', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '删除本人特征', exact: true })).toBeDisabled()
  const status = (await (await page.request.get('/api/face/status')).json()).data
  expect(status.available).toBe(false)
  expect(status.storageAvailable).toBe(true)
  expect([status.detection, status.comparison, status.liveness]).toEqual([false, false, false])
  await expect(page.getByRole('button', { name: '人脸服务未就绪', exact: true })).toBeDisabled()
  await expect(page.getByRole('link', { name: '前往本人登记与授权', exact: true })).toHaveCount(0)
  expect(status.enrolled).toBe(false)
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  const response = await page.request.post('/api/face/enrollment', {
    headers: { [csrf.headerName]: csrf.token },
    multipart: { consent: 'true' },
  })
  expect(response.status()).toBe(503)
})

test('登记更新删除的界面契约：真实采集JPEG、版本更新、明确删除确认（模拟SDK接口）', async ({
  page,
}) => {
  await collectionCamera(page)
  const status = simulatedStatus(),
    methods = []
  await page.route('**/api/face/status', (route) => route.fulfill({ json: envelope(status) }))
  await page.route('**/api/face/enrollment*', async (route) => {
    const request = route.request(),
      method = request.method()
    methods.push(method)
    if (method === 'DELETE') {
      expect(request.url()).toContain('expectedRevision=2')
      status.enrolled = false
      status.revision = 0
      status.deletionAvailable = false
    } else {
      expect(request.headers()['content-type']).toContain('multipart/form-data; boundary=')
      const body = request.postDataBuffer().toString('latin1')
      expect(body).toContain('name="image"; filename="capture.jpg"')
      expect(body).toContain('Content-Type: image/jpeg')
      expect(body).not.toContain('name="userId"')
      if (method === 'PUT') expect(body).toContain('name="expectedRevision"')
      status.enrolled = true
      status.revision++
      status.deletionAvailable = true
    }
    await route.fulfill({
      json: envelope({ enrolled: status.enrolled, revision: status.revision }),
    })
  })
  await login(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/attendance/face')
  await expect(page.getByText('登记与验证暂不可用', { exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: '前往本人登记与授权', exact: true }).click()
  await expect(page).toHaveURL(/\/attendance\/face#face-enrollment$/)
  await expect(
    page.getByRole('heading', { name: '本人登记与授权管理', exact: true }),
  ).toBeInViewport()
  await openCollection(page)
  await page.getByRole('button', { name: '采集并登记人脸', exact: true }).click()
  await expect(page.getByText('已登记 · 版本 1', { exact: true })).toBeVisible()
  await openCollection(page)
  await page.getByRole('button', { name: '采集并更新人脸', exact: true }).click()
  await expect(page.getByText('已登记 · 版本 2', { exact: true })).toBeVisible()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '删除本人特征', exact: true }).click()
  await expect(page.getByText('尚未登记本人特征', { exact: true })).toBeVisible()
  expect(methods).toEqual(['POST', 'PUT', 'DELETE'])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('验证界面绑定当前请求，换二维码失效，打卡不信任客户端成功布尔值（模拟SDK接口）', async ({
  page,
  context,
}) => {
  await collectionCamera(page)
  await context.grantPermissions(['geolocation'])
  await context.setGeolocation({ latitude: 31.2, longitude: 121.5, accuracy: 10 })
  const status = { ...simulatedStatus(), enrolled: true, revision: 1, punchRequired: true }
  let binding, punch
  await page.route('**/api/face/status', (route) => route.fulfill({ json: envelope(status) }))
  await page.route('**/api/face/challenges', (route) => {
    binding = route.request().postDataJSON()
    return route.fulfill({
      json: envelope({
        id: '00000000-0000-0000-0000-000000000001',
        expiresAt: new Date(Date.now() + 30000).toISOString(),
      }),
    })
  })
  await page.route('**/api/face/verification', (route) =>
    route.fulfill({
      json: envelope({
        verified: true,
        verificationId: '00000000-0000-0000-0000-000000000001',
        expiresAt: new Date(Date.now() + 30000).toISOString(),
      }),
    }),
  )
  await page.route('**/api/attendance/me/punches', (route) => {
    punch = route.request().postDataJSON()
    // Do not fabricate an attendance success in this UI-only test.
    return route.fulfill({
      status: 400,
      json: {
        code: 'ATTENDANCE_INVALID_INPUT',
        message: '测试接口拒绝，未生成考勤事件',
        data: null,
      },
    })
  })
  await login(page)
  await page.goto('/attendance')
  await page.getByLabel('场所挑战令牌', { exact: true }).fill('payload.signature')
  await page.getByRole('button', { name: '获取当前位置', exact: true }).click()
  await expect(page.getByText('定位已获取', { exact: true })).toBeVisible()
  const submit = page.getByRole('button', { name: '确认打卡', exact: true })
  await expect(submit).toBeDisabled()
  await openCollection(page)
  await page.getByRole('button', { name: '采集并验证本次打卡', exact: true }).click()
  await expect(submit).toBeEnabled()
  const clock = page.locator('.b-clock'),
    before = await clock.innerText()
  await expect(clock).not.toHaveText(before)
  await expect(submit).toBeEnabled()
  await page.getByLabel('场所挑战令牌', { exact: true }).fill('different.signature')
  await expect(submit).toBeDisabled()
  await openCollection(page)
  await page.getByRole('button', { name: '采集并验证本次打卡', exact: true }).click()
  await expect(submit).toBeEnabled()
  page.once('dialog', (dialog) => dialog.accept())
  await submit.click()
  await expect(
    page.getByRole('status').filter({ hasText: '测试接口拒绝，未生成考勤事件' }),
  ).toBeVisible()
  expect(punch.requestId).toBe(binding.requestId)
  expect(punch.token).toBe(binding.token)
  expect(punch.type).toBe(binding.type)
  expect(punch.faceVerificationId).toBe('00000000-0000-0000-0000-000000000001')
  expect(punch).not.toHaveProperty('verified')
})

test('手机验收报告保留未执行项，下载不包含坐标令牌和人脸（自动化浏览器报告）', async ({ page }) => {
  await login(page)
  await page.goto('/attendance/device')
  await page.getByRole('button', { name: '下载本机验收报告', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('请填写实际手机型号')
  await page.getByLabel('手机型号', { exact: true }).fill('自动化浏览器，并非实际手机')
  await page.getByLabel('系统版本', { exact: true }).fill('Linux测试环境')
  await page.getByLabel('浏览器版本', { exact: true }).fill('Chromium测试环境')
  await page.getByLabel('可信HTTPS与登录会话', { exact: true }).selectOption('FAIL')
  const downloading = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载本机验收报告', exact: true }).click()
  const download = await downloading
  const report = JSON.parse(await readFile(await download.path(), 'utf8'))
  expect(report.checks.filter((item) => item.result === 'NOT_RUN')).toHaveLength(9)
  expect(report.checks[0].result).toBe('FAIL')
  expect(report.faceSdkAcceptance).toBe('NOT_TESTED')
  for (const value of ['latitude', 'longitude', 'token', 'cookie', 'feature', 'image'])
    expect(JSON.stringify(report).toLowerCase()).not.toContain(`"${value}"`)
})
