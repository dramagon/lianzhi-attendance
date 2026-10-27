import { test, expect } from '@playwright/test'

async function login(page, role) {
  await page.goto('/login')
  await page.getByRole('button', { name: role, exact: true }).click()
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}

test('管理员登录、账号筛选、刷新保留会话、退出后失去权限', async ({ page }) => {
  await login(page, '管理员')
  await expect(page.getByRole('heading', { name: '演示管理员，欢迎回来' })).toBeVisible()
  await page.getByRole('link', { name: '系统账号' }).first().click()
  // A1允许持久新增员工；按真实API数量校验，保留账号筛选和刷新回归。
  const response = await page.request.get('/api/admin/accounts')
  expect(response.ok()).toBe(true)
  const count = (await response.json()).data.length
  expect(count).toBeGreaterThanOrEqual(3)
  await expect(page.locator('tbody tr')).toHaveCount(count)
  await page.getByRole('textbox', { name: '搜索账号' }).fill('DEMO003')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await expect(page.locator('tbody')).toContainText('演示员工')
  await page.reload()
  await expect(page).toHaveURL(/\/accounts$/)
  await expect(page.locator('tbody tr')).toHaveCount(count)
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/accounts')
  await expect(page).toHaveURL(/\/login\?redirect=/)
})

test('人事角色不能打开系统账号或直接调用管理员接口', async ({ page }) => {
  await login(page, '人事专员')
  await expect(page.getByRole('link', { name: '系统账号' })).toHaveCount(0)
  const status = await page.evaluate(async () => (await fetch('/api/admin/accounts')).status)
  expect(status).toBe(403)
  await page.goto('/accounts')
  await expect(page.getByRole('heading', { name: '当前账号没有访问权限' })).toBeVisible()
})

test('员工手机端可查看身份，考勤待开发且管理接口不可访问', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await login(page, '员工')
  await expect(page.getByRole('heading', { name: '今日考勤' })).toBeVisible()
  await expect(page.getByText('打卡功能即将接入')).toBeVisible()
  await expect(page.getByRole('heading', { name: '我的身份' })).toBeVisible()
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  )
  expect(overflow).toBe(false)
  await page.getByRole('button', { name: '打开菜单' }).click()
  await page.getByRole('link', { name: '个人信息', exact: true }).click()
  await expect(page.getByRole('heading', { name: '基础资料' })).toBeVisible()
  const status = await page.evaluate(async () => (await fetch('/api/management/overview')).status)
  expect(status).toBe(403)
})

test('错误密码提示可读，未登录访问受保护页面会跳转', async ({ page }) => {
  await page.goto('/profile')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.getByLabel('账号', { exact: true }).fill('admin')
  await page.getByLabel('密码', { exact: true }).fill('wrong-password')
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page.getByRole('alert')).toContainText('账号或密码错误')
})

test('登录页在手机上无横向溢出，未知路由显示404', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 })
  await page.goto('/login')
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(
    false,
  )
  await login(page, '管理员')
  await page.goto('/missing-page')
  await expect(page.getByRole('heading', { name: '这个页面暂时找不到' })).toBeVisible()
})
