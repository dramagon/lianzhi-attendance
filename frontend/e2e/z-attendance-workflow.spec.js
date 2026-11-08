import { test, expect } from '@playwright/test'
import QRCode from 'qrcode'

async function login(page, username, password = 'Lianzhi@2026') {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}
async function post(request, path, body) {
  const csrf = (await (await request.get('/api/auth/csrf')).json()).data
  const response = await request.post(`/api${path}`, {
    data: body,
    headers: { [csrf.headerName]: csrf.token },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).data
}
const dateInShanghai = () =>
  new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())

for (const coordinates of [
  { system: 'WGS84', latitude: 31.2, longitude: 121.5, gpsLatitude: 31.2, gpsLongitude: 121.5 },
  {
    system: 'GCJ02',
    latitude: 39.9102265,
    longitude: 116.4037136,
    gpsLatitude: 39.908823,
    gpsLongitude: 116.39747,
  },
]) {
  test(`正式排班→真实二维码图片识别→定位→上下班→本人日记录，越权查询被拒绝（${coordinates.system}）`, async ({
    page,
    browser,
  }) => {
    await login(page, 'admin')
    const company = (await (await page.request.get('/api/employees/me')).json()).data.companyId
    const stamp = Date.now().toString()
    const name = `扫码联调${stamp}`
    await post(page.request, '/workplaces', {
      companyId: company,
      name,
      latitude: coordinates.latitude,
      longitude: coordinates.longitude,
      coordinateSystem: coordinates.system,
      radiusMeters: 200,
      enabled: true,
    })
    const place = (
      await (await page.request.get(`/api/workplaces?companyId=${company}&page=1&size=100`)).json()
    ).data.items.find((p) => p.name === name)
    const username = `b${stamp}`
    const employee = await post(page.request, '/employees', {
      username,
      displayName: name,
      employeeNo: `B${stamp}`,
      role: 'EMPLOYEE',
      companyId: company,
      departmentId: null,
      enabled: true,
      password: 'Test@2026',
      workplaceIds: [place.id],
    })
    const date = dateInShanghai()
    const rule = {
      start: '00:00',
      end: '23:59',
      breakStart: null,
      breakEnd: null,
      graceMinutes: 0,
      workdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'],
    }
    await page.goto('/attendance/schedules')
    await expect(page.getByRole('heading', { name: '排班与日考勤', exact: true })).toBeVisible()
    await page.getByLabel('员工', { exact: true }).selectOption(String(employee.userId))
    await page
      .getByLabel('有效绑定场所（WGS84 / GCJ02）', { exact: true })
      .selectOption(String(place.id))
    await page.getByLabel('上班时间', { exact: true }).fill('00:00')
    await page.getByLabel('下班时间', { exact: true }).fill('23:59')
    await page.getByLabel('休息开始', { exact: true }).fill('')
    await page.getByLabel('休息结束', { exact: true }).fill('')
    await page.getByLabel('迟到宽限（分钟）', { exact: true }).fill('0')
    for (const day of ['周一', '周二', '周三', '周四', '周五', '周六', '周日'])
      await page.getByLabel(day, { exact: true }).check()
    await page.getByRole('button', { name: '保存正式排班', exact: true }).click()
    await expect(page.getByRole('status').filter({ hasText: '正式排班已保存' })).toBeVisible()
    await expect(page.getByRole('button', { name: '保存正式排班', exact: true })).toBeEnabled()
    await page.screenshot({ path: '../artifacts/member-b-20261027/schedule.png', fullPage: true })
    await page.goto('/attendance')
    await page.getByLabel('授权场所', { exact: true }).selectOption(String(place.id))
    await page.getByRole('button', { name: '生成短时挑战', exact: true }).click()
    await expect(page.getByAltText('办公场所打卡二维码', { exact: true })).toBeVisible()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: '../artifacts/member-b-20261027/qr.png', fullPage: true })
    const context = await browser.newContext({
      geolocation: {
        latitude: coordinates.gpsLatitude,
        longitude: coordinates.gpsLongitude,
        accuracy: 10,
      },
      permissions: ['geolocation'],
    })
    try {
      const worker = await context.newPage()
      worker.on('dialog', (d) => d.accept())
      await login(worker, username, 'Test@2026')
      await worker.goto('/attendance')
      await expect(worker.getByText('服务可用', { exact: true })).toBeVisible()
      await worker.getByRole('button', { name: '获取当前位置', exact: true }).click()
      await expect(worker.getByText('定位已获取', { exact: true })).toBeVisible()
      for (const type of ['IN', 'OUT']) {
        const challenge = await post(
          page.request,
          `/attendance/workplaces/${place.id}/challenge`,
          {},
        )
        const png = await QRCode.toBuffer(challenge.token, { width: 600, margin: 4 })
        await worker.getByLabel('打卡类型', { exact: true }).selectOption(type)
        await worker
          .getByLabel('识别二维码图片', { exact: true })
          .setInputFiles({ name: 'attendance.png', mimeType: 'image/png', buffer: png })
        await expect(worker.getByLabel('场所挑战令牌', { exact: true })).toHaveValue(
          challenge.token,
        )
        let posts = 0
        if (type === 'OUT') {
          // The real server commits the event, then the browser loses its response.
          await worker.route('**/api/attendance/me/punches', async (route) => {
            posts++
            const response = await route.fetch()
            expect(response.ok()).toBeTruthy()
            await route.abort('connectionreset')
          })
        }
        await worker.getByRole('button', { name: '确认打卡', exact: true }).click()
        if (type === 'OUT') {
          await expect(
            worker.getByText('上次打卡结果待核对，已暂停新的提交。', { exact: true }),
          ).toBeVisible()
          const saved = await worker.evaluate(() =>
            Object.entries(sessionStorage).filter(([key]) =>
              key.startsWith('lianzhi.b.pending-punch.'),
            ),
          )
          expect(saved).toHaveLength(1)
          expect(Object.keys(JSON.parse(saved[0][1])).sort()).toEqual(['requestId', 'type'])
          await expect(worker.getByRole('button', { name: '确认打卡', exact: true })).toBeDisabled()
          await worker.reload()
          await expect(
            worker.getByRole('status').filter({ hasText: '已确认打卡成功' }),
          ).toBeVisible()
          expect(posts).toBe(1)
          expect(
            await worker.evaluate(() =>
              Object.keys(sessionStorage).some((key) => key.startsWith('lianzhi.b.pending-punch.')),
            ),
          ).toBe(false)
        } else {
          await expect(worker.getByRole('status')).toContainText('打卡成功')
        }
      }
      await worker.goto('/attendance/records')
      await expect(worker.locator('tbody tr')).toHaveCount(2)
      await worker.goto('/attendance/schedules')
      await expect(worker.locator('tbody tr')).toHaveCount(1)
      await worker.screenshot({ path: '../artifacts/member-b-20261027/daily.png', fullPage: true })
      await worker.setViewportSize({ width: 390, height: 844 })
      expect(await worker.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      )
      await worker.screenshot({
        path: '../artifacts/member-b-20261027/daily-mobile.png',
        fullPage: true,
      })
      const forbidden = await worker.request.get(
        `/api/attendance/days?userId=1&from=${date}&to=${date}`,
      )
      expect(forbidden.status()).toBe(403)
      const csrf = (await (await worker.request.get('/api/auth/csrf')).json()).data
      const denied = await worker.request.post('/api/attendance/schedules', {
        data: {
          userId: employee.userId,
          workplaceId: place.id,
          businessDate: date,
          rule,
          holiday: false,
        },
        headers: { [csrf.headerName]: csrf.token },
      })
      expect(denied.status()).toBe(403)
    } finally {
      await context.close()
    }
  })
}
