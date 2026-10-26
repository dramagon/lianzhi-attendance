import { test, expect } from '@playwright/test'

test('管理员、人事和员工均可预览上传头像，刷新保留并恢复名称头像', async ({ page }) => {
  test.setTimeout(60000)
  for (const role of ['管理员', '人事专员', '员工']) {
    await page.goto('/login')
    await page.getByRole('button', { name: role, exact: true }).click()
    await page.getByRole('button', { name: '登录工作台' }).click()
    await expect(page).toHaveURL(/\/workbench$/)
    await page.goto('/profile')
    await expect(page.locator('.profile-card .user-avatar')).not.toHaveText('理员')
    const image = await page.evaluate(() => {
      const canvas = document.createElement('canvas')
      canvas.width = 80
      canvas.height = 60
      const context = canvas.getContext('2d')
      context.fillStyle = '#728acb'
      context.fillRect(0, 0, 80, 60)
      context.fillStyle = '#e5ecff'
      context.fillRect(25, 15, 30, 30)
      return canvas.toDataURL('image/png').split(',')[1]
    })
    await page.getByLabel('选择头像文件').setInputFiles({
      name: 'avatar.png',
      mimeType: 'image/png',
      buffer: Buffer.from(image, 'base64'),
    })
    await expect(page.locator('.profile-card .user-avatar img')).toHaveAttribute('src', /^blob:/)
    await page.getByRole('button', { name: '保存头像', exact: true }).click()
    await expect(page.getByRole('status')).toContainText('头像已更新')
    await expect(page.locator('.topbar-user .user-avatar img')).toHaveAttribute(
      'src',
      /^\/api\/profile\/avatars\//,
    )
    await page.reload()
    await expect(page.locator('.profile-card .user-avatar img')).toBeVisible()
    await expect
      .poll(() =>
        page
          .locator('.profile-card .user-avatar img')
          .evaluate((image) => image.complete && image.naturalWidth === 320),
      )
      .toBe(true)
    if (role === '管理员')
      await page.screenshot({ path: '../artifacts/avatar-profile.png', fullPage: true })
    if (role === '员工') {
      await page.setViewportSize({ width: 390, height: 844 })
      await expect(page.locator('.sidebar')).not.toBeInViewport()
      expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(
        false,
      )
      await page.screenshot({ path: '../artifacts/avatar-mobile.png', fullPage: true })
    }
    await page.getByRole('button', { name: '恢复默认头像', exact: true }).click()
    await expect(page.locator('.profile-card .user-avatar img')).toHaveCount(0)
    await expect(page.locator('.profile-card .user-avatar')).toContainText(
      role === '管理员' ? '管理员' : role === '人事专员' ? '人事' : '员工',
    )
    if (role === '管理员')
      await page.screenshot({ path: '../artifacts/avatar-default.png', fullPage: true })
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await expect(page).toHaveURL(/\/login$/)
    await page.setViewportSize({ width: 1280, height: 720 })
  }
})
