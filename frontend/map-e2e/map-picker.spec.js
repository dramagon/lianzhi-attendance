import { test, expect } from '@playwright/test'

// 只仿真高德SDK，业务接口继续走隔离演示后端。
const sdk = `
window.mapTest = { instances: [], markers: [] };
window.AMap = {
  Map: class {
    constructor() { this.handlers = {}; this.destroyed = false; window.mapTest.instances.push(this); }
    on(name, fn) { this.handlers[name] = fn; }
    setCenter(position) { this.center = position; }
    destroy() { this.destroyed = true; }
  },
  Marker: class {
    constructor(options) { this.position = options.position; this.map = options.map; window.mapTest.markers.push(this); }
    setMap(map) { this.map = map; }
  }
};`
async function login(page) {
  await page.goto('/login')
  await page.getByRole('button', { name: '管理员', exact: true }).click()
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/workbench$/)
  await page.goto('/organization')
  await page.getByRole('button', { name: '办公场所', exact: true }).click()
}
async function open(page) {
  await page.getByRole('button', { name: '新增办公场所', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
}
async function ready(page) {
  await expect(page.getByText('点击地图预览位置，确认采用后才填入场所坐标。')).toBeVisible()
}
async function pick(page) {
  await page.evaluate(() =>
    window.mapTest.instances
      .at(-1)
      .handlers.click({ lnglat: { getLat: () => 31.2345678, getLng: () => 121.456789 } }),
  )
}

test('地图标记与预览需确认，坐标系明确，改坐标清除旧选点，关闭销毁实例', async ({ page }) => {
  await page.route('https://webapi.amap.com/maps?*', (route) =>
    route.fulfill({ contentType: 'application/javascript', body: sdk }),
  )
  await login(page)
  await open(page)
  await ready(page)
  await page.getByLabel('纬度', { exact: true }).fill('30')
  await page.getByLabel('经度', { exact: true }).fill('120')
  await pick(page)
  await expect(page.getByText(/地图位置预览（GCJ02）/)).toBeVisible()
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue('30')
  expect(await page.evaluate(() => window.mapTest.markers.at(-1).position)).toEqual([
    121.456789, 31.2345678,
  ])
  await page.getByRole('button', { name: '采用地图位置', exact: true }).click()
  await expect(page.getByLabel('坐标系', { exact: true })).toHaveValue('GCJ02')
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue('31.2345678')
  await pick(page)
  await page.getByLabel('纬度', { exact: true }).fill('32')
  await expect(page.getByRole('button', { name: '采用地图位置', exact: true })).toHaveCount(0)
  await pick(page)
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  await page.getByRole('button', { name: '放弃地图位置', exact: true }).click()
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue('32')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.mapTest.instances[0].destroyed)).toBe(true)
  expect(await page.evaluate(() => window.mapTest.markers.every((m) => m.map === null))).toBe(true)
})

test('加载失败可重试，成功后再次打开复用SDK', async ({ page }) => {
  let calls = 0
  await page.route('https://webapi.amap.com/maps?*', (route) => {
    calls++
    return calls === 1
      ? route.abort()
      : route.fulfill({ contentType: 'application/javascript', body: sdk })
  })
  await login(page)
  await open(page)
  await page.getByRole('button', { name: '重新加载地图', exact: true }).click()
  await ready(page)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await open(page)
  await ready(page)
  expect(calls).toBe(2)
  expect(await page.evaluate(() => window.mapTest.instances.length)).toBe(2)
})

test('加载期间关闭并重开只加载一次，旧弹窗不创建地图', async ({ page }) => {
  let release,
    calls = 0
  const held = new Promise((resolve) => {
    release = resolve
  })
  await page.route('https://webapi.amap.com/maps?*', async (route) => {
    calls++
    await held
    await route.fulfill({ contentType: 'application/javascript', body: sdk })
  })
  await login(page)
  await open(page)
  await expect.poll(() => calls).toBe(1)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await open(page)
  release()
  await ready(page)
  expect(calls).toBe(1)
  expect(await page.evaluate(() => window.mapTest.instances.length)).toBe(1)
})

test('加载超时明确失败，允许重新加载', async ({ page }) => {
  let release
  const held = new Promise((resolve) => {
    release = resolve
  })
  await page.route('https://webapi.amap.com/maps?*', async (route) => {
    await held
    await route.abort()
  })
  await login(page)
  await page.clock.install()
  await open(page)
  await page.clock.fastForward(12500)
  await expect(page.getByText(/地图加载失败或超时/)).toBeVisible()
  await expect(page.getByRole('button', { name: '重新加载地图', exact: true })).toBeEnabled()
  release()
})
