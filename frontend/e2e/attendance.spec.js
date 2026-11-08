import { test, expect } from '@playwright/test'

async function employeeLogin(page) {
  await page.goto('/login')
  await page.getByRole('button', { name: '员工', exact: true }).click()
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/workbench$/)
  const entry = page.getByRole('link', { name: '考勤工作台' })
  const menu = page.getByRole('button', { name: '打开菜单', exact: true })
  if (await menu.isVisible()) await menu.click()
  await entry.click()
}

test('考勤服务已接通，缺少定位或令牌时不能提交，记录来自真实查询', async ({ page }) => {
  await employeeLogin(page)
  await expect(page.getByText('服务可用', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '确认打卡', exact: true })).toBeDisabled()
  await page.getByRole('link', { name: '考勤记录', exact: true }).click()
  await expect(page.getByRole('button', { name: '查询记录', exact: true })).toBeEnabled()
  await expect(page.getByText('考勤存储尚未接入', { exact: true })).toHaveCount(0)
})

test('试算使用后端真实结果，覆盖正常、迟到、缺卡、跨天和休息日', async ({ page }) => {
  await employeeLogin(page)
  await page.getByRole('link', { name: '排班与规则', exact: true }).click()
  const result = page.locator('.b-result')
  for (const [name, status, hours] of [
    ['正常班次', '正常出勤', '8小时0分钟'],
    ['迟到', '迟到', '7小时48分钟'],
    ['缺卡', '缺卡', '0小时0分钟'],
    ['跨天班次', '正常出勤', '7小时0分钟'],
    ['休息日', '休息日', '0小时0分钟'],
  ]) {
    await page.getByRole('button', { name, exact: true }).click()
    await expect(result.locator('.tag').first()).toHaveText(status)
    await expect(result.locator('.b-work-minutes')).toHaveText(hours)
    await expect(page.getByRole('button', { name: '计算考勤结果', exact: true })).toBeEnabled()
  }
})

test('修改字段清除旧结果，错误休息区间显示可读反馈', async ({ page }) => {
  await employeeLogin(page)
  await page.getByRole('link', { name: '排班与规则', exact: true }).click()
  await page.getByRole('button', { name: '正常班次', exact: true }).click()
  await expect(page.locator('.b-work-minutes')).toBeVisible()
  await page.getByLabel('休息开始（可留空）', { exact: true }).fill('20:00')
  await expect(page.locator('.b-work-minutes')).toHaveCount(0)
  await page.getByRole('button', { name: '计算考勤结果', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('休息时间须在班次内')
})

test('人脸状态真实，摄像头拒绝后可重试', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
      value: async () => {
        throw new DOMException('denied', 'NotAllowedError')
      },
    })
  })
  await employeeLogin(page)
  await page.getByRole('link', { name: '人脸管理', exact: true }).click()
  await expect(page.locator('.b-capability-row .tag')).toHaveText(['未接入', '未接入', '未接入'])
  await expect(page.getByRole('button', { name: '开启本机预览', exact: true })).toBeDisabled()
  await page.getByRole('checkbox', { name: /我同意仅开启本机摄像头预览/ }).check()
  await page.getByRole('button', { name: '开启本机预览', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('摄像头权限被拒绝')
  await expect(page.getByRole('button', { name: '开启本机预览', exact: true })).toBeEnabled()
})

test('撤回授权与切换页面释放摄像头，不上传图像', async ({ page }) => {
  await page.addInitScript(() => {
    window.cameraStops = 0
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
      value: async () => {
        const canvas = document.createElement('canvas')
        canvas.width = 100
        canvas.height = 100
        canvas.getContext('2d').fillRect(0, 0, 100, 100)
        const stream = canvas.captureStream(1)
        for (const track of stream.getTracks()) {
          const stop = track.stop.bind(track)
          track.stop = () => {
            window.cameraStops++
            stop()
          }
        }
        return stream
      },
    })
    HTMLMediaElement.prototype.play = () => Promise.resolve()
  })
  const uploads = []
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/api/face/')) uploads.push(r.url())
  })
  await employeeLogin(page)
  await page.getByRole('link', { name: '人脸管理', exact: true }).click()
  const consent = page.getByRole('checkbox', { name: /我同意仅开启本机摄像头预览/ })
  await consent.check()
  await page.getByRole('button', { name: '开启本机预览', exact: true }).click()
  await expect(page.locator('.b-camera-label')).toBeVisible()
  await consent.uncheck()
  await expect.poll(() => page.evaluate(() => window.cameraStops)).toBe(1)
  await consent.check()
  await page.getByRole('button', { name: '开启本机预览', exact: true }).click()
  await expect(page.locator('.b-camera-label')).toBeVisible()
  await page.getByRole('link', { name: '今日打卡', exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.cameraStops)).toBe(2)
  expect(uploads).toEqual([])
})

test('手机四个考勤页无横向溢出，刷新保留会话，未登录不能直达', async ({ page }) => {
  await page.goto('/attendance/rules')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.setViewportSize({ width: 390, height: 844 })
  await employeeLogin(page)
  for (const title of ['今日打卡', '考勤记录', '排班与规则', '人脸管理']) {
    await page.getByRole('link', { name: title, exact: true }).click()
    // Lazy route loading may finish after click; do not reload the previous page mid-navigation.
    await expect(page.getByRole('link', { name: title, exact: true })).toHaveClass(/selected/)
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true)
  }
  await page.reload()
  await expect(page).toHaveURL(/\/attendance\/face$/)
  await expect(page.getByRole('heading', { name: '摄像头检查（不识别）' })).toBeVisible()
})
