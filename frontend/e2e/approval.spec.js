import { test, expect } from '@playwright/test'

async function login(page, role) {
  await page.goto('/login')
  await page.getByRole('button', { name: role, exact: true }).click()
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}

test('员工校验、真实提交、本人记录与撤回，输入改变清除旧结果', async ({ page }) => {
  await login(page, '员工')
  await page.goto('/approvals')
  await expect(page.getByRole('button', { name: '提交申请', exact: true })).toBeEnabled()
  await page.getByLabel('开始时间', { exact: true }).fill('2026-10-27T09:00')
  await page.getByLabel('结束时间', { exact: true }).fill('2026-10-27T18:00')
  await page.getByLabel('申请事由', { exact: true }).fill('审批校验测试')
  await page.getByRole('button', { name: '校验申请内容', exact: true }).click()
  await expect(page.getByText('内容校验通过，尚未保存申请', { exact: true })).toBeVisible()
  await page.getByLabel('申请事由', { exact: true }).fill('修改后的申请事由')
  await expect(page.getByText('内容校验通过，尚未保存申请', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '提交申请', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '已提交，等待审批' })).toBeVisible()
  const records = (await (await page.request.get('/api/approvals/me')).json()).data
  const entry = records.items.find(
    (e) => e.request.draft.reason === '修改后的申请事由' && e.request.state === 'PENDING',
  )
  expect(entry).toBeTruthy()
  await page.getByRole('button', { name: `查看 #${entry.request.id}`, exact: true }).click()
  await page.getByRole('button', { name: '撤回申请', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '已撤回申请。' })).toBeVisible()
  await expect(
    page.getByRole('heading', { name: `申请 #${entry.request.id} · 已撤回`, exact: true }),
  ).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.sidebar')).not.toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  await page.screenshot({ path: '../artifacts/a2-approval-mobile.png', fullPage: true })
})

test('审批入口和后台接口都限制管理角色，历史读取真实记录', async ({ page }) => {
  await login(page, '员工')
  await page.goto('/approvals/review')
  await expect(page.getByRole('heading', { name: '当前账号没有访问权限' })).toBeVisible()
  expect((await page.request.get('/api/approvals/inbox')).status()).toBe(403)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  expect((await page.request.get('/api/auth/session')).status()).toBe(401)
  await login(page, '人事专员')
  await page.goto('/approvals/review')
  await expect(page.getByRole('heading', { name: '审批工作台', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '审批历史', exact: true }).click()
  expect((await page.request.get('/api/approvals/inbox?state=PROCESSED')).status()).toBe(200)
  await expect(page.getByText('审批历史尚未接入')).toHaveCount(0)
})

async function post(request, path, data) {
  const csrf = (await (await request.get('/api/auth/csrf')).json()).data
  const response = await request.post(`/api${path}`, {
    data,
    headers: { [csrf.headerName]: csrf.token },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return (await response.json()).data
}

test('真实申请→人事同意→考勤请假来源，重复审批不会重复调整', async ({ page, browser }) => {
  await login(page, '管理员')
  const companyId = (await (await page.request.get('/api/employees/me')).json()).data.companyId
  const stamp = Date.now().toString(),
    name = `审批联调${stamp}`,
    username = `ap${stamp}`
  await post(page.request, '/workplaces', {
    companyId,
    name,
    latitude: 31,
    longitude: 121,
    coordinateSystem: 'WGS84',
    radiusMeters: 100,
    enabled: true,
  })
  const place = (
    await (await page.request.get(`/api/workplaces?companyId=${companyId}&size=100`)).json()
  ).data.items.find((p) => p.name === name)
  const employee = await post(page.request, '/employees', {
    username,
    displayName: name,
    employeeNo: username,
    role: 'EMPLOYEE',
    companyId,
    departmentId: null,
    enabled: true,
    password: '1234',
    workplaceIds: [place.id],
  })
  const date = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(Date.now() + 86400000))
  await post(page.request, '/attendance/schedules', {
    userId: employee.userId,
    businessDate: date,
    workplaceId: place.id,
    rule: {
      start: '09:00',
      end: '18:00',
      breakStart: '12:00',
      breakEnd: '13:00',
      graceMinutes: 5,
      workdays: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'],
    },
    holiday: false,
  })
  const context = await browser.newContext()
  try {
    const worker = await context.newPage()
    await worker.goto('/login')
    await worker.getByLabel('账号', { exact: true }).fill(username)
    await worker.getByLabel('密码', { exact: true }).fill('1234')
    await worker.getByRole('button', { name: '登录工作台' }).click()
    await expect(worker).toHaveURL(/\/workbench$/)
    await worker.goto('/approvals')
    await worker.getByLabel('开始时间', { exact: true }).fill(`${date}T09:00`)
    await worker.getByLabel('结束时间', { exact: true }).fill(`${date}T18:00`)
    await worker.getByLabel('申请事由', { exact: true }).fill(name)
    await worker.getByRole('button', { name: '提交申请', exact: true }).click()
    await expect(worker.getByRole('status').filter({ hasText: '已提交，等待审批' })).toBeVisible()
    const entry = (await (await worker.request.get('/api/approvals/me')).json()).data.items[0]
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await expect(page).toHaveURL(/\/login$/)
    expect((await page.request.get('/api/auth/session')).status()).toBe(401)
    await login(page, '人事专员')
    await page.goto('/approvals/review')
    await page.getByRole('button', { name: `查看 #${entry.request.id}`, exact: true }).click()
    await page.getByRole('button', { name: '同意申请并同步考勤', exact: true }).click()
    await expect(
      page.getByRole('status').filter({ hasText: '已同意申请，考勤调整已同步。' }),
    ).toBeVisible()
    await post(page.request, `/approvals/${entry.request.id}/actions`, {
      action: 'APPROVE',
      expectedVersion: 0,
      note: '',
    })
    await worker.reload()
    await worker.getByRole('button', { name: '我的申请记录', exact: true }).click()
    await worker.getByRole('button', { name: `查看 #${entry.request.id}`, exact: true }).click()
    await expect(
      worker.getByRole('heading', { name: `申请 #${entry.request.id} · 已同意`, exact: true }),
    ).toBeVisible()
    const detail = (await (await worker.request.get(`/api/approvals/${entry.request.id}`)).json())
      .data
    expect(detail.history).toHaveLength(2)
    const days = (
      await (await worker.request.get(`/api/attendance/me/days?from=${date}&to=${date}`)).json()
    ).data
    expect(days[0].attendance.status).toBe('ON_LEAVE')
    expect(days[0].leaveMinutes).toBe(480)
    expect(days[0].approvalIds).toEqual([entry.request.id])
    await worker.goto('/assistant')
    await worker.getByLabel('开始日期', { exact: true }).fill(date)
    await worker.getByLabel('结束日期', { exact: true }).fill(date)
    await worker.getByRole('button', { name: '查询本人考勤', exact: true }).click()
    await expect(worker.getByText('有效工时 0 分钟 · 请假 480 分钟 · 加班 0 分钟')).toBeVisible()
    await expect(worker.getByText(`审批来源：${entry.request.id}`, { exact: true })).toBeVisible()
    await worker.getByRole('button', { name: '查询本人申请', exact: true }).click()
    await expect(
      worker.getByText(`申请 #${entry.request.id} · 已通过`, { exact: true }),
    ).toBeVisible()
    await worker.getByRole('link', { name: '查看完整申请与审计', exact: true }).click()
    await worker.getByRole('button', { name: '我的申请记录', exact: true }).click()
    await worker.getByRole('button', { name: `查看 #${entry.request.id}`, exact: true }).click()
    await worker.setViewportSize({ width: 390, height: 844 })
    await expect(worker.locator('.sidebar')).not.toBeInViewport()
    expect(await worker.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(
      false,
    )
    await worker.screenshot({ path: '../artifacts/a3-approved-mobile.png', fullPage: true })
  } finally {
    await context.close()
  }
})
