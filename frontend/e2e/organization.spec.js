import { test, expect } from '@playwright/test'

async function login(page, role) {
  await page.goto('/login')
  await page.getByRole('button', { name: role, exact: true }).click()
  await page.getByRole('button', { name: '登录工作台' }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}

test('管理员建公司、部门、场所、员工并修改停用，刷新后记录仍存在', async ({ page }) => {
  test.setTimeout(60000)
  const suffix = Date.now().toString()
  const company = `验收公司${suffix}`
  const department = `验收部门${suffix}`
  const place = `验收场所${suffix}`
  const employee = `验收员工${suffix}`
  await login(page, '管理员')
  await page.getByRole('link', { name: '组织与场所', exact: true }).click()
  await page.getByRole('button', { name: '公司', exact: true }).click()
  await page.getByRole('button', { name: '新增公司', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('名称', { exact: true }).fill(company)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByLabel('筛选公司').selectOption({ label: company })
  await page.getByRole('button', { name: '部门', exact: true }).click()
  await page.getByRole('button', { name: '新增部门', exact: true }).click()
  await dialog.getByLabel('名称', { exact: true }).fill(department)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '办公场所', exact: true }).click()
  await page.getByRole('button', { name: '新增办公场所', exact: true }).click()
  await dialog.getByLabel('名称', { exact: true }).fill(place)
  await dialog.getByLabel('纬度', { exact: true }).fill('31.2304')
  await dialog.getByLabel('经度', { exact: true }).fill('121.4737')
  await expect(dialog.getByText('地图选点尚未接入', { exact: false })).toBeVisible()
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '员工', exact: true }).click()
  await page.getByRole('button', { name: '新增员工', exact: true }).click()
  await dialog.getByLabel('登录账号', { exact: true }).fill(`test${suffix}`)
  await dialog.getByLabel('姓名', { exact: true }).fill(employee)
  await dialog.getByLabel('工号', { exact: true }).fill(`E${suffix}`)
  await dialog.getByLabel('初始密码（至少12位）', { exact: true }).fill('TestPassword123!')
  await dialog
    .getByRole('combobox', { name: '部门', exact: true })
    .selectOption({ label: department })
  await dialog.getByLabel(place, { exact: true }).check()
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  let row = page.getByRole('row').filter({ hasText: employee })
  await expect(row).toContainText(department)
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  await expect(dialog.getByLabel(place, { exact: true })).toBeChecked()
  await dialog.getByRole('combobox', { name: '状态', exact: true }).selectOption({ label: '停用' })
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(row).toContainText('停用')
  await page.reload()
  await page.getByLabel('搜索员工').fill(`E${suffix}`)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  row = page.getByRole('row').filter({ hasText: employee })
  await expect(row).toContainText('停用')
  const data = await page.evaluate(
    async (keyword) => (await (await fetch(`/api/employees?query=${keyword}`)).json()).data,
    `E${suffix}`,
  )
  expect(data.total).toBe(1)
  expect(data.items[0]).not.toHaveProperty('passwordHash')
  await page.screenshot({ path: '../artifacts/a1-organization.png', fullPage: true })
})

test('员工不能进入组织管理，个人资料显示公司和办公场所', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await login(page, '员工')
  await page.goto('/organization')
  await expect(page.getByRole('heading', { name: '当前账号没有访问权限' })).toBeVisible()
  await page.goto('/profile')
  await expect(page.getByText('所属公司', { exact: true })).toBeVisible()
  await expect(page.getByText('办公场所', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  await page.screenshot({ path: '../artifacts/a1-profile-mobile.png', fullPage: true })
})
