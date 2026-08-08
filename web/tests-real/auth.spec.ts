import { expect, test } from '@playwright/test'

test('registers, rotates the session, logs out, and signs in again through the real gateway', async ({ page, request, context }) => {
  const unique = `${Date.now()}-${Math.random().toString(16).slice(2)}`
  const email = `real-e2e-${unique}@${'observatory'.repeat(4)}.example.test`
  const password = 'OrbitReader2026!'
  const displayName = `真实观测者-${unique.slice(-6)}`
  expect(email.length).toBeGreaterThan(64)
  expect(email.length).toBeLessThanOrEqual(128)

  await test.step('register through the browser', async () => {
    await page.goto('/auth?redirect=/')
    await page.getByRole('group', { name: '认证方式' }).getByRole('button', { name: '注册' }).click()
    await page.getByLabel('显示名称').fill(displayName)
    await page.getByLabel('邮箱').fill(email)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '创建账号' }).click()

    await expect(page).toHaveURL('/')
    await expect(page.getByRole('link', { name: displayName })).toBeVisible()
    expect(await page.evaluate(() => Object.keys(localStorage).filter(key => /token/i.test(key)))).toEqual([])
  })

  const cookiesAfterRegistration = await context.cookies()
  const initialRefresh = cookiesAfterRegistration.find(cookie => cookie.name === 'dc_refresh')
  const initialCsrf = cookiesAfterRegistration.find(cookie => cookie.name === 'dc_csrf')
  expect(initialRefresh).toMatchObject({ httpOnly: true, path: '/api/auth', sameSite: 'Lax' })
  expect(initialCsrf).toMatchObject({ httpOnly: false, path: '/', sameSite: 'Lax' })

  await test.step('reload rotates the refresh token and restores the in-memory session', async () => {
    await page.reload()
    await expect(page.getByRole('link', { name: displayName })).toBeVisible()
    const rotated = (await context.cookies()).find(cookie => cookie.name === 'dc_refresh')
    expect(rotated?.value).toBeTruthy()
    expect(rotated?.value).not.toBe(initialRefresh?.value)
  })

  const cookiesBeforeLogout = await context.cookies()
  const refreshBeforeLogout = cookiesBeforeLogout.find(cookie => cookie.name === 'dc_refresh')
  const csrfBeforeLogout = cookiesBeforeLogout.find(cookie => cookie.name === 'dc_csrf')
  expect(refreshBeforeLogout).toBeTruthy()
  expect(csrfBeforeLogout).toBeTruthy()

  await test.step('logout clears cookies and revokes the server-side refresh token', async () => {
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL('/')
    await expect(page.getByRole('link', { name: displayName })).toHaveCount(0)
    await expect(page.getByRole('link', { name: '登录' })).toBeVisible()
    expect((await context.cookies()).find(cookie => cookie.name === 'dc_refresh')).toBeUndefined()

    const replay = await request.post('/api/auth/refresh', {
      headers: {
        Cookie: `dc_refresh=${refreshBeforeLogout!.value}; dc_csrf=${csrfBeforeLogout!.value}`,
        'X-CSRF-Token': csrfBeforeLogout!.value,
        'Idempotency-Key': crypto.randomUUID()
      }
    })
    expect(replay.status()).toBe(401)
    await expect(replay.json()).resolves.toMatchObject({ code: 'TOKEN_REVOKED' })
  })

  await test.step('duplicate registration proves persistence and existing credentials still log in', async () => {
    await page.goto('/auth?redirect=/')
    const authMode = page.getByRole('group', { name: '认证方式' })
    await authMode.getByRole('button', { name: '注册' }).click()
    await page.getByLabel('显示名称').fill(displayName)
    await page.getByLabel('邮箱').fill(email)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '创建账号' }).click()
    await expect(page.getByRole('alert')).toContainText('该邮箱已注册')

    await authMode.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByLabel('邮箱').fill(email)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '提交登录' }).click()
    await expect(page).toHaveURL('/')
    await expect(page.getByRole('link', { name: displayName })).toBeVisible()
  })
})
