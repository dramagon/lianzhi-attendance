import { chromium } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const target = new URL('../../docs/screenshots/', import.meta.url)
await mkdir(target, { recursive: true })
const browser = await chromium.launch()
try {
  const desktop = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
  })
  const page = await desktop.newPage()
  await page.goto('http://127.0.0.1:5173/login')
  await page.getByRole('button', { name: '管理员', exact: true }).waitFor()
  await page.screenshot({ path: fileURLToPath(new URL('login.png', target)), fullPage: true })
  await page.getByRole('button', { name: '管理员', exact: true }).click()
  await page.getByRole('button', { name: '登录工作台' }).click()
  await page.waitForURL('**/workbench')
  await page.getByRole('heading', { name: '我的身份' }).waitFor()
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: fileURLToPath(new URL('workbench.png', target)), fullPage: true })
  const mobile = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 1,
    isMobile: true,
    hasTouch: true,
  })
  const phone = await mobile.newPage()
  await phone.goto('http://127.0.0.1:5173/login')
  await phone.getByRole('button', { name: '员工', exact: true }).click()
  await phone.getByRole('button', { name: '登录工作台' }).click()
  await phone.waitForURL('**/workbench')
  await phone.getByRole('heading', { name: '今日考勤' }).waitFor()
  await phone.waitForLoadState('networkidle')
  await phone.screenshot({
    path: fileURLToPath(new URL('employee-mobile.png', target)),
    fullPage: true,
  })
  console.log('Preview screenshots saved to docs/screenshots/')
} finally {
  await browser.close()
}
