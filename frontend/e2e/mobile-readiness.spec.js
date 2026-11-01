import { test, expect } from '@playwright/test'
import QRCode from 'qrcode'

async function login(page, role = '员工') {
  await page.goto('/login')
  await page.getByRole('button', { name: role, exact: true }).click()
  await page.getByRole('button', { name: '登录工作台', exact: true }).click()
  await expect(page).toHaveURL(/\/workbench$/)
}
const envelope = (data) => ({ code: 'OK', message: '操作成功', data })
const rule = {
  start: '09:00',
  end: '18:00',
  breakStart: '12:00',
  breakEnd: '13:00',
  graceMinutes: 5,
  workdays: ['MONDAY'],
}

test('快速切换员工时，旧场所响应不能覆盖新员工', async ({ page }) => {
  await login(page, '管理员')
  let release,
    requested = false
  const held = new Promise((resolve) => {
    release = resolve
  })
  await page.route('**/api/employees?*', (route) =>
    route.fulfill({
      json: envelope({
        items: [
          { userId: 901, displayName: '测试甲', employeeNo: 'A' },
          { userId: 902, displayName: '测试乙', employeeNo: 'B' },
        ],
      }),
    }),
  )
  await page.route('**/api/employees/*/workplaces', async (route) => {
    const id = Number(route.request().url().split('/').at(-2))
    if (id === 901) {
      requested = true
      await held
    }
    await route.fulfill({
      json: envelope(
        id === 1 ? [] : [{ id, name: `场所${id}`, enabled: true, coordinateSystem: 'WGS84' }],
      ),
    })
  })
  await page.route('**/api/attendance/schedules?*', (route) =>
    route.fulfill({ json: envelope([]) }),
  )
  await page.route('**/api/attendance/days?*', (route) => route.fulfill({ json: envelope([]) }))
  await page.goto('/attendance/schedules')
  await page.getByLabel('员工', { exact: true }).selectOption('901')
  await expect.poll(() => requested).toBe(true)
  await page.getByLabel('员工', { exact: true }).selectOption('902')
  await expect(page.getByLabel('有效绑定场所（WGS84）', { exact: true })).toHaveValue('902')
  const response = page.waitForResponse((r) => r.url().endsWith('/employees/901/workplaces'))
  release()
  await response
  await page.evaluate(() => new Promise(requestAnimationFrame))
  await expect(page.getByLabel('有效绑定场所（WGS84）', { exact: true })).toHaveValue('902')
  await expect(page.getByRole('option', { name: '场所901', exact: true })).toHaveCount(0)
})

test('日期变化立即清空旧结果，旧日记录请求不能覆盖新查询', async ({ page }) => {
  await login(page)
  let release,
    waiting = 0
  const held = new Promise((resolve) => {
    release = resolve
  })
  const handler = async (route) => {
    const request = new URL(route.request().url()),
      fresh = request.searchParams.get('from') === '2026-10-01'
    if (!fresh) {
      waiting++
      await held
    }
    const date = fresh ? '2026-10-02' : '2026-09-01'
    const data = request.pathname.endsWith('/days')
      ? [
          {
            businessDate: date,
            attendance: { status: 'NORMAL', workMinutes: 480 },
            leaveMinutes: 0,
            overtimeMinutes: 0,
            approvalIds: [],
          },
        ]
      : [{ businessDate: date, rule }]
    await route.fulfill({ json: envelope(data) })
  }
  await page.route('**/api/attendance/me/schedules?*', handler)
  await page.route('**/api/attendance/me/days?*', handler)
  await page.goto('/attendance/schedules')
  await expect.poll(() => waiting).toBe(2)
  await page.getByLabel('开始日期', { exact: true }).fill('2026-10-01')
  await page.getByLabel('结束日期', { exact: true }).fill('2026-10-02')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('tbody')).toContainText('2026-10-02')
  const oldResponses = [
    page.waitForResponse(
      (r) => r.url().includes('/me/schedules?') && !r.url().includes('from=2026-10-01'),
    ),
    page.waitForResponse(
      (r) => r.url().includes('/me/days?') && !r.url().includes('from=2026-10-01'),
    ),
  ]
  release()
  await Promise.all(oldResponses)
  await page.evaluate(() => new Promise(requestAnimationFrame))
  await expect(page.locator('tbody')).toContainText('2026-10-02')
  await expect(page.locator('tbody')).not.toContainText('2026-09-01')
  await page.getByLabel('开始日期', { exact: true }).fill('2026-10-02')
  await expect(page.locator('tbody tr')).toHaveCount(0)
})

async function delayFirstBitmap(page) {
  await page.addInitScript(() => {
    const original = window.createImageBitmap.bind(window)
    window.bitmapCloses = 0
    window.bitmapPending = false
    let first = true
    window.createImageBitmap = async (...args) => {
      const bitmap = await original(...args)
      const close = bitmap.close.bind(bitmap)
      bitmap.close = () => {
        window.bitmapCloses++
        close()
      }
      if (!first) return bitmap
      first = false
      window.bitmapPending = true
      return new Promise((resolve) => {
        window.resolveBitmap = () => resolve(bitmap)
      })
    }
  })
}
const image = async (token) => ({
  name: 'qr.png',
  mimeType: 'image/png',
  buffer: await QRCode.toBuffer(token, { width: 500 }),
})

test('图片识别期间切页，旧图片被释放且不回填令牌', async ({ page }) => {
  await delayFirstBitmap(page)
  await login(page)
  await page.goto('/attendance')
  await page
    .getByLabel('识别二维码图片', { exact: true })
    .setInputFiles(await image('oldpayload.signature'))
  await expect.poll(() => page.evaluate(() => window.bitmapPending)).toBe(true)
  await page.getByRole('link', { name: '考勤记录', exact: true }).click()
  await expect(page).toHaveURL(/\/attendance\/records$/)
  await expect(page.getByRole('heading', { name: '我的考勤记录', exact: true })).toBeVisible()
  await page.evaluate(() => window.resolveBitmap())
  await expect.poll(() => page.evaluate(() => window.bitmapCloses)).toBe(1)
  await page.getByRole('link', { name: '今日打卡', exact: true }).click()
  await expect(page.getByLabel('场所挑战令牌', { exact: true })).toHaveValue('')
})

test('新图片替代旧图片，晚到的旧识别结果不能覆盖新结果', async ({ page }) => {
  await delayFirstBitmap(page)
  await login(page)
  await page.goto('/attendance')
  const upload = page.getByLabel('识别二维码图片', { exact: true })
  await upload.setInputFiles(await image('oldpayload.signature'))
  await expect.poll(() => page.evaluate(() => window.bitmapPending)).toBe(true)
  await upload.setInputFiles(await image('newpayload.signature'))
  await expect(page.getByLabel('场所挑战令牌', { exact: true })).toHaveValue('newpayload.signature')
  await page.evaluate(() => window.resolveBitmap())
  await expect.poll(() => page.evaluate(() => window.bitmapCloses)).toBe(2)
  await expect(page.getByLabel('场所挑战令牌', { exact: true })).toHaveValue('newpayload.signature')
})

test('取消摄像头等待后可重试，旧授权失败不能关闭新预览', async ({ page }) => {
  await page.addInitScript(() => {
    let first = true
    window.qrStops = 0
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
      value: async () => {
        if (first) {
          first = false
          return new Promise((resolve, reject) => {
            window.rejectOldCamera = () => reject(new DOMException('denied', 'NotAllowedError'))
          })
        }
        const canvas = document.createElement('canvas')
        canvas.width = 100
        canvas.height = 100
        canvas.getContext('2d').fillRect(0, 0, 100, 100)
        const stream = canvas.captureStream(1)
        for (const track of stream.getTracks()) {
          const stop = track.stop.bind(track)
          track.stop = () => {
            window.qrStops++
            stop()
          }
        }
        return stream
      },
    })
    HTMLMediaElement.prototype.play = () => Promise.resolve()
  })
  await login(page)
  await page.goto('/attendance')
  await page.getByRole('button', { name: '摄像头扫码', exact: true }).click()
  await page.getByRole('button', { name: '取消识别 / 授权等待', exact: true }).click()
  await page.getByRole('button', { name: '摄像头扫码', exact: true }).click()
  await expect(page.getByLabel('扫码摄像头', { exact: true })).toBeVisible()
  await page.evaluate(() => window.rejectOldCamera())
  await page.evaluate(() => new Promise(requestAnimationFrame))
  await expect(page.getByLabel('扫码摄像头', { exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.getByRole('button', { name: '关闭扫码', exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.qrStops)).toBe(1)
})

test('手机设备检查能处理真实接口、仿真定位、权限拒绝和断网恢复', async ({ page, context }) => {
  await context.grantPermissions(['geolocation'])
  await context.setGeolocation({ latitude: 31.2, longitude: 121.5, accuracy: 10 })
  await page.addInitScript(() =>
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
      value: async () => {
        throw new DOMException('denied', 'NotAllowedError')
      },
    }),
  )
  await login(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/attendance/device')
  await expect(page.getByText('已连接联智服务', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '检查真实定位', exact: true }).click()
  await expect(page.getByText(/已获取定位，精度约 10 米/)).toBeVisible()
  await page
    .getByRole('checkbox', { name: '允许仅在本机预览，不上传、不登记', exact: true })
    .check()
  await page.getByRole('button', { name: '检查摄像头', exact: true }).click()
  await expect(page.getByText('摄像头权限被拒绝，请允许后重试', { exact: true })).toBeVisible()
  await context.setOffline(true)
  await expect(page.getByText('设备报告离线', { exact: true })).toBeVisible()
  await context.setOffline(false)
  await page.getByRole('button', { name: '检查服务连接', exact: true }).click()
  await expect(page.getByText('已连接联智服务', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

// Controlled browser media/position callbacks test lifecycle only, not a physical phone or SDK.
async function prepareFaceCamera(page) {
  await page.addInitScript(() => {
    window.faceStops = 0
    window.faceCalls = []
    const media = () => {
      const canvas = document.createElement('canvas')
      canvas.width = canvas.height = 100
      const stream = canvas.captureStream(1)
      for (const track of stream.getTracks()) {
        const stop = track.stop.bind(track)
        track.stop = () => {
          window.faceStops++
          stop()
        }
      }
      return stream
    }
    Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
      value: () =>
        new Promise((resolve, reject) =>
          window.faceCalls.push({
            allow: () => resolve(media()),
            deny: () => reject(new DOMException('denied', 'NotAllowedError')),
          }),
        ),
    })
    HTMLMediaElement.prototype.play = () => Promise.resolve()
  })
  await login(page)
  await page.goto('/attendance/face')
  await page.getByRole('checkbox', { name: /我同意仅开启本机摄像头预览/ }).check()
}

test('人脸预览取消后旧授权成功只释放旧设备，旧失败不关闭新预览', async ({ page }) => {
  await prepareFaceCamera(page)
  const open = page.getByRole('button', { name: '开启本机预览', exact: true })
  await open.click()
  await page.getByRole('button', { name: '取消授权等待', exact: true }).click()
  await open.click()
  await page.evaluate(() => window.faceCalls[1].allow())
  await expect(page.getByLabel('本机摄像头预览', { exact: true })).toBeVisible()
  await page.evaluate(() => window.faceCalls[0].allow())
  await expect.poll(() => page.evaluate(() => window.faceStops)).toBe(1)
  await expect(page.getByLabel('本机摄像头预览', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '关闭摄像头', exact: true }).click()
  await open.click()
  await page.getByRole('button', { name: '取消授权等待', exact: true }).click()
  await open.click()
  await page.evaluate(() => window.faceCalls[3].allow())
  await expect(page.getByLabel('本机摄像头预览', { exact: true })).toBeVisible()
  await page.evaluate(() => window.faceCalls[2].deny())
  await page.evaluate(() => new Promise(requestAnimationFrame))
  await expect(page.getByLabel('本机摄像头预览', { exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.getByRole('checkbox', { name: /我同意仅开启本机摄像头预览/ }).uncheck()
  await expect.poll(() => page.evaluate(() => window.faceStops)).toBe(3)
})

test('人脸预览切后台释放设备，晚到的播放失败不污染新预览', async ({ page }) => {
  await prepareFaceCamera(page)
  await page.evaluate(() => {
    HTMLMediaElement.prototype.play = () =>
      new Promise((resolve, reject) => {
        window.rejectOldPlay = () => reject(new DOMException('interrupted', 'AbortError'))
      })
  })
  await page.getByRole('button', { name: '开启本机预览', exact: true }).click()
  await page.evaluate(() => window.faceCalls[0].allow())
  await expect.poll(() => page.evaluate(() => !!window.rejectOldPlay)).toBe(true)
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    HTMLMediaElement.prototype.play = () => Promise.resolve()
  })
  await expect.poll(() => page.evaluate(() => window.faceStops)).toBe(1)
  await page.getByRole('button', { name: '开启本机预览', exact: true }).click()
  await page.evaluate(() => window.faceCalls[1].allow())
  await page.evaluate(() => window.rejectOldPlay())
  await expect(page.getByLabel('本机摄像头预览', { exact: true })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.getByRole('link', { name: '考勤记录', exact: true }).click()
  await expect(page).toHaveURL(/\/attendance\/records$/)
  await expect.poll(() => page.evaluate(() => window.faceStops)).toBe(2)
})

test('切页后的旧定位不能覆盖重新获取的位置', async ({ page }) => {
  await page.addInitScript(() => {
    window.locations = []
    navigator.geolocation.getCurrentPosition = (success) => window.locations.push(success)
  })
  await login(page)
  await page.goto('/attendance')
  await page.getByRole('button', { name: '获取当前位置', exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.locations.length)).toBe(1)
  await page.getByRole('link', { name: '考勤记录', exact: true }).click()
  await expect(page).toHaveURL(/\/attendance\/records$/)
  await page.getByRole('link', { name: '今日打卡', exact: true }).click()
  await page.getByRole('button', { name: '获取当前位置', exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.locations.length)).toBe(2)
  await page.evaluate(() => {
    window.locations[1]({
      coords: { latitude: 31.2, longitude: 121.5, accuracy: 10 },
      timestamp: Date.now(),
    })
    window.locations[0]({
      coords: { latitude: 0, longitude: 0, accuracy: 999 },
      timestamp: Date.now(),
    })
  })
  await expect(page.getByText('精度约 10 米 · 仅在本次打卡发送', { exact: true })).toBeVisible()
})

test('未查到待确认请求时不自动重发，核对后重新准备会清除旧输入', async ({ page, context }) => {
  await login(page)
  const user = (await (await page.request.get('/api/auth/session')).json()).data
  await page.evaluate((id) => {
    sessionStorage.setItem(
      `lianzhi.b.pending-punch.${id}`,
      JSON.stringify({ requestId: '00000000-0000-0000-0000-000000000001', type: 'IN' }),
    )
  }, user.id)
  let posts = 0
  let receipts = 0
  page.on('request', (request) => {
    if (request.url().includes('/attendance/me/punches/requests/')) receipts++
  })
  await page.route('**/api/attendance/me/punches', (route) => {
    posts++
    return route.abort()
  })
  await page.goto('/attendance')
  await expect(page.getByText(/暂未查到本次记录/)).toBeVisible()
  expect(receipts).toBe(1)
  await context.setOffline(true)
  await context.setOffline(false)
  await expect.poll(() => receipts).toBe(2)
  await expect(page.getByText(/暂未查到本次记录/)).toBeVisible()
  await expect(page.getByRole('button', { name: '确认打卡', exact: true })).toBeDisabled()
  await expect(page.getByLabel('场所挑战令牌', { exact: true })).toBeDisabled()
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: '重新准备打卡', exact: true }).click()
  await expect(page.getByLabel('场所挑战令牌', { exact: true })).toBeEnabled()
  await expect(page.getByLabel('场所挑战令牌', { exact: true })).toHaveValue('')
  await expect(page.getByRole('button', { name: '确认打卡', exact: true })).toBeDisabled()
  expect(posts).toBe(0)
})
