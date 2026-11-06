import { test, expect } from '@playwright/test'
import { writeFile, readFile } from 'node:fs/promises'

test('整站演示真实串联报表、DOCX解析、员工来源引用与CSV下载', async ({ page }) => {
  test.setTimeout(90000)
  if (!(await classroom(page))) return
  const crashes = []
  page.on('pageerror', (error) => crashes.push(error.message))
  await page.getByRole('button', { name: '一键整站演示', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('整站演示已完成', { timeout: 65000 })
  await expect(page.getByTestId('demo-report')).toContainText('请假480分钟')
  await expect(page.getByTestId('demo-document')).toContainText('.docx · PARSED')
  await expect(page.getByTestId('demo-answer')).toContainText('非生成式AI')
  await expect(page.getByTestId('demo-answer')).toContainText('480分钟')
  const username = await page.evaluate(
    () => JSON.parse(localStorage.getItem('lianzhi.presentation.v2')).username,
  )
  await expect(page.getByTestId('demo-answer')).toContainText(username)
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载本轮CSV', exact: true }).click()
  const csv = await download
  expect(csv.suggestedFilename()).toContain(username)
  const content = await readFile(await csv.path(), 'utf8')
  expect(content).toContain('480')
  expect(content).toContain(`SHOW${username.slice(4)}`)
  await page.screenshot({ path: '../artifacts/classroom-full-success.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../artifacts/classroom-full-mobile.png', fullPage: true })
  await page.getByRole('link', { name: '统计报表与图表', exact: true }).click()
  await expect(page.getByRole('heading', { name: /统计|报表/ }).first()).toBeVisible()
  await page.goto('/knowledge')
  await expect(
    page.getByRole('heading', { name: `课堂制度-${username}.docx · 第1版`, exact: true }),
  ).toBeVisible()
  await page.goto('/assistant')
  await expect(page.getByLabel('制度问答状态')).toContainText('离线原文摘录')
  expect(crashes).toEqual([])
})

test('整站演示遇到解析失败即停止，不把失败文档展示成回答', async ({ page }) => {
  test.setTimeout(90000)
  if (!(await classroom(page))) return
  let asks = 0
  page.on('request', (req) => {
    if (new URL(req.url()).pathname === '/api/assistant/ask') asks++
  })
  await page.route('**/api/knowledge/documents/*', async (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    const response = await route.fetch()
    const body = await response.json()
    body.data.processing = 'FAILED'
    body.data.message = '模拟解析失败'
    await route.fulfill({ response, json: body })
  })
  await page.getByRole('button', { name: '一键整站演示', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('模拟解析失败', { timeout: 65000 })
  await expect(page.getByRole('status')).toContainText('自动演示已暂停')
  await expect(page.getByTestId('demo-answer')).toHaveCount(0)
  expect(asks).toBe(0)
})

async function classroom(page) {
  await page.goto('/login?redirect=/attendance/presentation')
  await page.getByRole('button', { name: '管理员', exact: true }).click()
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect.poll(() => new URL(page.url()).pathname).toBe('/attendance/presentation')
  if (process.env.CLASSROOM_RESTORE === '1')
    await expect(page.getByRole('status')).toContainText('已从后台核对并恢复')
  const response = await page.request.get('/api/attendance/presentation')
  if (process.env.CLASSROOM_E2E !== '1') {
    expect(response.status()).toBe(404)
    await expect(page.getByRole('button', { name: '一键完整演示', exact: true })).toBeDisabled()
    return false
  }
  expect(response.ok()).toBe(true)
  return true
}

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

test('一键展示真实跑通，场所超过100条仍可准备，关页后恢复只读取结果', async ({ page, context }) => {
  if (!(await classroom(page))) return
  const companyId = (await (await page.request.get('/api/employees/me')).json()).data.companyId
  const csrf = (await (await page.request.get('/api/auth/csrf')).json()).data
  for (let index = 0; index < 101; index++) {
    const response = await page.request.post('/api/workplaces', {
      headers: { [csrf.headerName]: csrf.token },
      data: {
        companyId,
        name: `分页演示场所${Date.now()}-${index}`,
        latitude: 31.2,
        longitude: 121.5,
        coordinateSystem: 'WGS84',
        radiusMeters: 200,
        enabled: true,
      },
    })
    expect(response.ok()).toBe(true)
  }
  let paged = false
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname === '/api/workplaces' && Number(url.searchParams.get('page')) > 1) paged = true
  })
  await page.getByRole('button', { name: '一键完整演示', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('完整演示已完成', { timeout: 15000 })
  await expect(page.locator('.milestones .done')).toHaveCount(5)
  expect(paged).toBe(true)
  const saved = await page.evaluate(() =>
    JSON.parse(localStorage.getItem('lianzhi.presentation.v2')),
  )
  for (const key of ['token', 'password', 'location', 'image'])
    expect(saved).not.toHaveProperty(key)
  const instance = (await (await page.request.get('/api/attendance/presentation')).json()).data
    .instance
  const state = await context.storageState()
  state.cookies = [] // Only synthetic scenario identifiers are carried across the restart test.
  await writeFile('../artifacts/classroom-resume.json', JSON.stringify(state))
  await writeFile(
    '../artifacts/classroom-before-restart.json',
    JSON.stringify({ username: saved.username, instance }),
  )
  await page.close()
  const reopened = await context.newPage()
  let writes = 0
  reopened.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      /\/api\/(attendance\/me\/punches|approvals(?:\/\d+\/actions)?)$/.test(
        new URL(request.url()).pathname,
      )
    )
      writes++
  })
  await reopened.goto('/attendance/presentation')
  await expect(reopened.getByRole('status')).toContainText('已从后台核对并恢复')
  await expect(reopened.getByText('获批请假 480 分钟', { exact: true })).toBeVisible()
  await expect(reopened.locator('.grid section').first().locator('.entry')).toHaveCount(2)
  expect(writes).toBe(0)
})

test('自动演示遇到写入失败即停止，不继续签退和申请', async ({ page }) => {
  if (!(await classroom(page))) return
  let punches = 0,
    applications = 0
  await page.route('**/api/attendance/me/punches', (route) => {
    punches++
    return route.fulfill({
      status: 400,
      json: { code: 'ATTENDANCE_INVALID_INPUT', message: '模拟失败：未创建打卡事件', data: null },
    })
  })
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/approvals')
      applications++
  })
  await page.getByRole('button', { name: '一键完整演示', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('模拟失败：未创建打卡事件')
  await expect(page.getByRole('status')).toContainText('自动演示已暂停')
  await expect(page.locator('.milestones .done')).toHaveCount(1)
  await expect(page.getByText('尚无打卡记录，请按顺序演示。', { exact: true })).toBeVisible()
  expect(punches).toBe(1)
  expect(applications).toBe(0)
})

test('浏览器禁止保存进度仍可完成展示', async ({ page }) => {
  if (!(await classroom(page))) return
  await page.evaluate(() => {
    Storage.prototype.setItem = () => {
      throw new DOMException('blocked', 'SecurityError')
    }
  })
  await page.getByRole('button', { name: '一键完整演示', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('完整演示已完成', { timeout: 15000 })
  await expect(page.getByText(/浏览器未允许长期保存进度/)).toBeVisible()
  await expect(page.getByText('获批请假 480 分钟', { exact: true })).toBeVisible()
})

test('暂停自动演示会等待当前真实写入，不再继续后续步骤', async ({ page }) => {
  if (!(await classroom(page))) return
  let release,
    pending = false,
    punches = 0
  const held = new Promise((resolve) => {
    release = resolve
  })
  await page.route('**/api/attendance/me/punches', async (route) => {
    punches++
    pending = true
    await held
    await route.continue()
  })
  await page.getByRole('button', { name: '一键完整演示', exact: true }).click()
  await expect.poll(() => pending).toBe(true)
  await page.getByRole('button', { name: '暂停自动演示', exact: true }).click()
  release()
  await expect(page.getByRole('status')).toContainText('自动演示已暂停')
  await expect(page.locator('.grid section').first().locator('.entry')).toHaveCount(1)
  await expect(page.locator('.milestones .done')).toHaveCount(2)
  await expect(page.getByText('审批状态：尚未申请', { exact: true })).toBeVisible()
  expect(punches).toBe(1)
})

test('恢复缓存身份不匹配时不展示其他员工结果', async ({ page }) => {
  if (!(await classroom(page))) return
  await page.getByRole('button', { name: '1. 准备演示数据', exact: true }).click()
  await expect(page.getByText('尚无打卡记录，请按顺序演示。', { exact: true })).toBeVisible()
  await page.evaluate(() => {
    const saved = JSON.parse(localStorage.getItem('lianzhi.presentation.v2'))
    saved.userId = 99999999
    localStorage.setItem('lianzhi.presentation.v2', JSON.stringify(saved))
  })
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('保存进度与当前演示身份不匹配')
  await expect(page.locator('.grid .entry')).toHaveCount(0)
  await expect(
    page.getByRole('button', { name: '2. 二维码识别并上班', exact: true }),
  ).toBeDisabled()
})

test('服务重启后恢复进度，不重新写入打卡或申请', async ({ page }) => {
  if (process.env.CLASSROOM_RESTORE !== '1') {
    if (await classroom(page))
      await expect(page.getByRole('button', { name: '恢复上次进度', exact: true })).toBeEnabled()
    return
  }
  const before = JSON.parse(await readFile('../artifacts/classroom-before-restart.json', 'utf8'))
  let writes = 0
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      /\/api\/(attendance\/me\/punches|approvals(?:\/\d+\/actions)?)$/.test(
        new URL(request.url()).pathname,
      )
    )
      writes++
  })
  if (!(await classroom(page))) return
  await expect(page.getByRole('status')).toContainText('已从后台核对并恢复')
  await expect(page.getByText('获批请假 480 分钟', { exact: true })).toBeVisible()
  const after = (await (await page.request.get('/api/attendance/presentation')).json()).data
  expect(after.instance).not.toBe(before.instance)
  const saved = await page.evaluate(() =>
    JSON.parse(localStorage.getItem('lianzhi.presentation.v2')),
  )
  expect(saved.username).toBe(before.username)
  await expect(page.locator('.grid section').first().locator('.entry')).toHaveCount(2)
  expect(writes).toBe(0)
})
