import { test, expect } from '@playwright/test'

const envelope = (data) => ({ code: 'OK', message: '操作成功', data })
async function setup(page) {
  await page.addInitScript(() => {
    window.positionRequests = []
    Object.defineProperty(navigator, 'geolocation', {
      value: {
        getCurrentPosition: (success, error) => window.positionRequests.push({ success, error }),
      },
    })
  })
  await page.goto('/login')
  await page.getByRole('button', { name: '管理员', exact: true }).click()
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/workbench$/)
  await page.route('**/api/organizations/companies?*', (route) =>
    route.fulfill({
      json: envelope({ items: [{ id: 801, name: '定位验证公司', enabled: true }], total: 1 }),
    }),
  )
  await page.route('**/api/organizations/departments?*', (route) =>
    route.fulfill({ json: envelope({ items: [], total: 0 }) }),
  )
  await page.route('**/api/workplaces?*', (route) =>
    route.fulfill({ json: envelope({ items: [], total: 0 }) }),
  )
  await page.goto('/organization')
}
async function openPlace(page) {
  await page.getByRole('button', { name: '办公场所', exact: true }).click()
  await page.getByRole('button', { name: '新增办公场所', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
}
async function deliver(page, index, accuracy = 150) {
  await page.evaluate(
    ({ index, accuracy }) =>
      window.positionRequests[index].success({
        coords: { latitude: 31.2345678, longitude: 121.456789, accuracy },
      }),
    { index, accuracy },
  )
}

test('设备坐标先预览精度，确认采用后才填入，手工修改使旧定位失效', async ({ page }) => {
  await setup(page)
  await openPlace(page)
  await page.getByLabel('纬度', { exact: true }).fill('30')
  await page.getByLabel('经度', { exact: true }).fill('120')
  await page.getByRole('button', { name: '采集设备位置', exact: true }).click()
  await deliver(page, 0)
  await expect(page.getByText('定位精度超过打卡半径，建议重新采集或核对准确坐标。')).toBeVisible()
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue('30')
  await page.getByRole('button', { name: '采用此位置', exact: true }).click()
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue('31.2345678')
  await expect(page.getByLabel('坐标系', { exact: true })).toHaveValue('WGS84')
  await page.getByRole('button', { name: '采集设备位置', exact: true }).click()
  await page.getByLabel('纬度', { exact: true }).fill('32')
  await deliver(page, 1, 10)
  await expect(page.getByRole('button', { name: '采用此位置', exact: true })).toHaveCount(0)
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue('32')
})

test('取消编辑后晚到的位置和错误不能污染新场所，失败后可重试', async ({ page }) => {
  await setup(page)
  await openPlace(page)
  await page.getByRole('button', { name: '采集设备位置', exact: true }).click()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await page.getByRole('button', { name: '新增办公场所', exact: true }).click()
  await deliver(page, 0, 10)
  await page.evaluate(() => window.positionRequests[0].error({ code: 1 }))
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue('')
  await expect(page.getByRole('button', { name: '采用此位置', exact: true })).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.getByRole('button', { name: '采集设备位置', exact: true }).click()
  await page.evaluate(() => window.positionRequests[1].error({ code: 1 }))
  await expect(page.getByRole('alert')).toContainText('定位失败')
  await page.getByRole('button', { name: '采集设备位置', exact: true }).click()
  await deliver(page, 2, 10)
  await page.getByRole('button', { name: '放弃此位置', exact: true }).click()
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue('')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
})

test('员工绑定读取期间切换分类，旧响应不能打开错误表单', async ({ page }) => {
  await setup(page)
  let release
  const held = new Promise((resolve) => {
    release = resolve
  })
  await page.route('**/api/employees?*', (route) =>
    route.fulfill({
      json: envelope({
        items: [
          {
            userId: 901,
            username: 'synthetic',
            displayName: '延迟读取验证',
            companyId: 801,
            role: 'EMPLOYEE',
            enabled: true,
          },
        ],
        total: 1,
      }),
    }),
  )
  await page.route('**/api/employees/901/workplaces', async (route) => {
    await held
    await route.fulfill({ json: envelope([]) })
  })
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByText('延迟读取验证', { exact: true })).toBeVisible()
  const pending = page.waitForRequest((r) => r.url().endsWith('/employees/901/workplaces'))
  await page.getByRole('button', { name: '编辑', exact: true }).click()
  await pending
  await page.getByRole('button', { name: '办公场所', exact: true }).click()
  const completed = page.waitForResponse((r) => r.url().endsWith('/employees/901/workplaces'))
  release()
  await completed
  await page.evaluate(() => new Promise(requestAnimationFrame))
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(page.getByText('延迟读取验证', { exact: true })).toHaveCount(0)
})
