import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

async function signIn(page, username = 'employee', password = 'Lianzhi@2026') {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}
async function post(request, path, data) {
  const csrf = (await (await request.get('/api/auth/csrf')).json()).data
  const response = await request.post(`/api${path}`, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  })
  expect(response.ok(), await response.text()).toBe(true)
  return (await response.json()).data
}

test('个人月历真实读取审批日结果，核对来源并导出本人CSV，手机布局不溢出', async ({
  page,
  request,
}) => {
  const csrf = (await (await request.get('/api/auth/csrf')).json()).data
  expect(
    (
      await request.post('/api/auth/login', {
        form: { username: 'admin', password: 'Lianzhi@2026' },
        headers: { [csrf.headerName]: csrf.token },
      })
    ).ok(),
  ).toBe(true)
  const companyId = (await (await request.get('/api/employees/me')).json()).data.companyId
  const stamp = Date.now().toString(),
    username = `month${stamp}`
  await post(request, '/workplaces', {
    companyId,
    name: username,
    latitude: 31.2,
    longitude: 121.5,
    coordinateSystem: 'WGS84',
    radiusMeters: 200,
    enabled: true,
  })
  const places = (await (await request.get('/api/workplaces?page=1&size=100')).json()).data
  const workplace = places.items.find((item) => item.name === username)
  expect(workplace).toBeTruthy()
  const employee = await post(request, '/employees', {
    username,
    password: 'Test@2026',
    displayName: '月历合成员工',
    employeeNo: username,
    role: 'EMPLOYEE',
    companyId,
    departmentId: null,
    enabled: true,
    workplaceIds: [workplace.id],
  })
  const server = (await (await request.get('/api/attendance/status')).json()).data
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date(Date.parse(server.serverTime) + 86400000))
  const date = ['year', 'month', 'day']
    .map((type) => parts.find((part) => part.type === type).value)
    .join('-')
  const month = date.slice(0, 7)
  const emptyDate = date === `${month}-01` ? `${month}-02` : `${month}-01`
  await post(request, '/attendance/schedules', {
    userId: employee.userId,
    workplaceId: workplace.id,
    businessDate: date,
    holiday: false,
    rule: {
      start: '09:00',
      end: '18:00',
      breakStart: '12:00',
      breakEnd: '13:00',
      graceMinutes: 5,
      workdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'],
    },
  })
  await signIn(page, username, 'Test@2026')
  const application = await post(page.request, '/approvals', {
    requestKey: crypto.randomUUID(),
    draft: {
      type: 'LEAVE',
      startAt: `${date}T09:00:00`,
      endAt: `${date}T18:00:00`,
      punchType: null,
      workplaceId: null,
      reason: '月历真实请假联调',
    },
  })
  await post(request, `/approvals/${application.request.id}/actions`, {
    action: 'APPROVE',
    expectedVersion: application.request.version,
    note: '月历展示测试',
  })
  await page.goto('/attendance/records')
  const panel = page.getByRole('region', { name: '个人月度考勤', exact: true })
  await page.getByLabel('考勤月份', { exact: true }).fill(month)
  await panel.getByRole('button', { name: `${date} 请假`, exact: true }).click()
  const detail = panel.getByLabel('选中日期详情')
  await expect(detail).toContainText('获批请假 480 分钟')
  await expect(detail).toContainText(`审批来源：${application.request.id}`)
  await expect(panel.getByLabel('本月记录汇总')).toContainText('1 天')
  const download = page.waitForEvent('download')
  await panel.getByRole('button', { name: '导出本人月度CSV', exact: true }).click()
  const file = await download
  expect(file.suggestedFilename()).toBe(`我的考勤-${month}.csv`)
  const csv = await readFile(await file.path(), 'utf8')
  expect(csv).toContain(`"${date}","请假"`)
  expect(csv).toContain('"480"')
  expect(csv).not.toMatch(/token|password|latitude|longitude/i)
  await panel.getByRole('button', { name: `${emptyDate} 无排班记录`, exact: true }).click()
  await expect(detail).toContainText('没有可查询的正式排班日记录')
  await panel.getByRole('button', { name: `${date} 请假`, exact: true }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.sidebar')).not.toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../artifacts/b-personal-month-mobile.png', fullPage: true })
})

test('月度查询失败明确报错，重试成功前不显示空汇总或允许导出', async ({ page }) => {
  await signIn(page)
  await page.route('**/api/attendance/me/days?*', (route) =>
    route.fulfill({
      status: 503,
      json: { code: 'UNAVAILABLE', message: '月度查询暂不可用', data: null },
    }),
  )
  await page.goto('/attendance/records')
  const panel = page.getByRole('region', { name: '个人月度考勤', exact: true })
  await expect(panel.getByRole('alert')).toContainText('月度查询暂不可用')
  await expect(panel.getByLabel('本月记录汇总')).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '导出本人月度CSV', exact: true })).toBeDisabled()
  await page.unroute('**/api/attendance/me/days?*')
  await panel.getByRole('button', { name: '刷新月度考勤', exact: true }).click()
  await expect(panel.getByLabel('本月记录汇总')).toBeVisible()
})

test('快速切换月份后，迟到的旧响应不能覆盖新月份（日接口仿真）', async ({ page }) => {
  await signIn(page)
  let release,
    pending = false
  const held = new Promise((resolve) => {
    release = resolve
  })
  await page.route('**/api/attendance/me/days?*', async (route) => {
    if (new URL(route.request().url()).searchParams.get('from') === '2026-02-01') {
      pending = true
      await held
      await route.fulfill({
        json: {
          data: [
            {
              businessDate: '2026-02-02',
              workplaceId: 1,
              attendance: {
                status: 'ABSENT',
                workMinutes: 0,
                lateMinutes: 0,
                earlyMinutes: 0,
                notes: [],
              },
              leaveMinutes: 0,
              overtimeMinutes: 0,
              approvalIds: [],
            },
          ],
        },
      })
    } else await route.fulfill({ json: { data: [] } })
  })
  await page.goto('/attendance/records')
  const panel = page.getByRole('region', { name: '个人月度考勤', exact: true })
  await page.getByLabel('考勤月份', { exact: true }).fill('2026-02')
  await expect.poll(() => pending).toBe(true)
  await page.getByLabel('考勤月份', { exact: true }).fill('2026-03')
  await expect(
    panel.getByText('本月没有正式排班日记录，不据此推断缺勤。', { exact: true }),
  ).toBeVisible()
  const staleResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/attendance/me/days' &&
      new URL(response.url()).searchParams.get('from') === '2026-02-01',
  )
  release()
  await staleResponse
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)))
  await expect(
    panel.getByRole('button', { name: '2026-03-02 无排班记录', exact: true }),
  ).toBeVisible()
  await expect(panel.getByRole('button', { name: /2026-02/ })).toHaveCount(0)
  await expect(panel.getByLabel('本月记录汇总')).toContainText('0 天')
})
