import { test, expect } from '@playwright/test'

async function login(page, role) {
  await page.goto('/login')
  await page.getByRole('button', { name: role, exact: true }).click()
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}

test('员工校验申请内容但不可假提交，输入改变清除旧结果', async ({ page }) => {
  await login(page, '员工')
  await page.goto('/approvals')
  await expect(page.getByText('申请保存与审批处理尚未接入', { exact: true })).toBeVisible()
  await page.getByLabel('开始时间', { exact: true }).fill('2026-10-27T09:00')
  await page.getByLabel('结束时间', { exact: true }).fill('2026-10-27T18:00')
  await page.getByLabel('申请事由', { exact: true }).fill('审批校验测试')
  await page.getByRole('button', { name: '校验申请内容', exact: true }).click()
  await expect(page.getByText('内容校验通过，尚未保存申请', { exact: true })).toBeVisible()
  await expect(
    page.getByRole('button', { name: '提交申请（尚未接入）', exact: true }),
  ).toBeDisabled()
  await page.getByLabel('申请事由', { exact: true }).fill('修改后的申请事由')
  await expect(page.getByText('内容校验通过，尚未保存申请', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '我的申请记录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '申请记录尚未接入' })).toBeVisible()
  expect((await page.request.get('/api/approvals/me')).status()).toBe(503)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.sidebar')).not.toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  await page.screenshot({ path: '../artifacts/a2-approval-mobile.png', fullPage: true })
})

test('审批入口和后台接口都限制管理角色，未接入历史不显示伪记录', async ({ page }) => {
  await login(page, '员工')
  await page.goto('/approvals/review')
  await expect(page.getByRole('heading', { name: '当前账号没有访问权限' })).toBeVisible()
  expect((await page.request.get('/api/approvals/inbox')).status()).toBe(403)
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await login(page, '人事专员')
  await page.goto('/approvals/review')
  await expect(page.getByRole('heading', { name: '审批工作台', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '审批历史', exact: true }).click()
  await expect(page.getByRole('heading', { name: '审批历史尚未接入' })).toBeVisible()
  expect((await page.request.get('/api/approvals/inbox')).status()).toBe(503)
})
