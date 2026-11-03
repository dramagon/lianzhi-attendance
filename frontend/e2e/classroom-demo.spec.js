import { test, expect } from '@playwright/test'

test('课堂展示：普通环境关闭，独立展示包真实跑通打卡与审批', async ({ page }) => {
  await page.goto('/login?redirect=/attendance/presentation')
  await page.getByRole('button', { name: '管理员', exact: true }).click()
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect.poll(() => new URL(page.url()).pathname).toBe('/attendance/presentation')
  const response = await page.request.get('/api/attendance/presentation')
  if (process.env.CLASSROOM_E2E !== '1') {
    expect(response.status()).toBe(404)
    await expect(page.getByRole('button', { name: '1. 准备演示数据', exact: true })).toBeDisabled()
    await expect(page.getByRole('alert')).toContainText('只在独立课堂展示包中开放')
    return
  }
  expect(response.ok()).toBe(true)
  expect((await response.json()).data.classroom).toBe(true)
  await page.getByRole('button', { name: '1. 准备演示数据', exact: true }).click()
  await expect(page.getByText('尚无打卡记录，请按顺序演示。', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '2. 二维码识别并上班', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('服务器已确认上班记录')
  await expect(page.getByAltText('本轮真实签名二维码', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '3. 二维码识别并下班', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('服务器已确认下班记录')
  await page.getByRole('button', { name: '4. 员工提交请假', exact: true }).click()
  await expect(page.getByText('审批状态：PENDING', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '5. 管理员同意申请', exact: true }).click()
  await expect(page.getByText('审批状态：APPROVED', { exact: true })).toBeVisible()
  await expect(page.getByText('获批请假 480 分钟', { exact: true })).toBeVisible()
  await expect(
    page.getByRole('button', { name: '2. 二维码识别并上班', exact: true }),
  ).toBeDisabled()
  await page.reload()
  await expect(page.getByText('获批请假 480 分钟', { exact: true })).toBeVisible()
  const events = await page.request.get('/api/attendance/me/records?from=2020-01-01&to=2030-01-01')
  expect(events.status()).toBe(400) // Original range validation still applies.
  const rows = page.locator('.grid section').first().locator('.entry')
  await expect(rows).toHaveCount(2)
  await page.screenshot({ path: '../artifacts/classroom-success.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../artifacts/classroom-success-mobile.png', fullPage: true })
})
