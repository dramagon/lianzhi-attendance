import { test, expect } from '@playwright/test'

async function post(request, path, data) {
  const csrf = (await (await request.get('/api/auth/csrf')).json()).data
  const response = await request.post(`/api${path}`, {
    headers: { [csrf.headerName]: csrf.token },
    data,
  })
  expect(response.ok()).toBe(true)
  return (await response.json()).data
}
async function login(page, username, password) {
  await page.goto('/login')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}

test('本人修改密码后旧密码与其他设备会话失效，新密码可登录', async ({ page, browser }) => {
  await login(page, 'admin', 'Lianzhi@2026')
  const companyId = (await (await page.request.get('/api/employees/me')).json()).data.companyId
  const username = `pw${Date.now()}`
  await post(page.request, '/employees', {
    username,
    displayName: '密码验证员工',
    employeeNo: username,
    role: 'EMPLOYEE',
    companyId,
    departmentId: null,
    enabled: true,
    password: 'old1',
    workplaceIds: [],
  })
  await post(page.request, '/auth/logout')
  await login(page, username, 'old1')
  const context = await browser.newContext()
  try {
    const other = await context.newPage()
    await login(other, username, 'old1')
    await page.goto('/profile')
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
    await page.getByLabel('当前密码', { exact: true }).fill('wrong')
    await page.getByLabel('新密码', { exact: true }).fill('new2')
    await page.getByLabel('确认新密码', { exact: true }).fill('new2')
    await page.getByRole('button', { name: '修改密码并重新登录', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('当前密码不正确')
    await page.getByLabel('当前密码', { exact: true }).fill('old1')
    await page.getByRole('button', { name: '修改密码并重新登录', exact: true }).click()
    await expect(page).toHaveURL(/\/login\?passwordChanged=1$/)
    await expect(page.getByRole('status')).toContainText('密码已修改')
    expect((await other.request.get('/api/auth/session')).status()).toBe(401)
    await page.getByLabel('账号', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill('old1')
    await page.getByRole('button', { name: '登录工作台', exact: true }).click()
    await expect(page.getByRole('alert')).toBeVisible()
    await page.getByLabel('密码', { exact: true }).fill('new2')
    await page.getByRole('button', { name: '登录工作台', exact: true }).click()
    await expect(page).toHaveURL(/\/workbench$/)
  } finally {
    await context.close()
  }
})
