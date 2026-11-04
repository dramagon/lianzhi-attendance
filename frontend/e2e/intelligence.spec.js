import { test, expect } from '@playwright/test'

test('助手只读查询与关闭能力真实，错误不伪装空结果', async ({ page }) => {
  await page.goto('/login')
  await page.getByRole('button', { name: '员工', exact: true }).click()
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/workbench$/)
  await page.goto('/assistant')
  await expect(page.getByRole('heading', { name: '业务助手', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '提问', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '查询本人申请', exact: true }).click()
  await expect(page.getByText(/来源：本人申请记录，共/)).toBeVisible()
  expect(
    (
      await page.request.get('/api/assistant/attendance?userId=1&from=2026-10-31&to=2026-10-31')
    ).status(),
  ).toBe(403)
  await page.route('**/api/assistant/attendance?*', (route) =>
    route.fulfill({
      status: 503,
      json: { code: 'UNAVAILABLE', message: '联调服务暂不可用', data: null },
    }),
  )
  await page.getByRole('button', { name: '查询本人考勤', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('联调服务暂不可用')
  await expect(page.getByText('所选日期没有排班日记录，不据此推断缺勤。')).toHaveCount(0)
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})
