import { expect, test, type Page } from '@playwright/test'

async function registerReader(page: Page) {
  const unique = `${Date.now()}-${Math.random().toString(16).slice(2)}`
  const displayName = `真实创作者${unique.slice(-6)}`

  await page.goto('/auth?redirect=/write')
  await page.getByRole('group', { name: '认证方式' }).getByRole('button', { name: '注册' }).click()
  await page.getByLabel('显示名称').fill(displayName)
  await page.getByLabel('邮箱').fill(`real-content-${unique}@example.test`)
  await page.getByLabel('密码').fill('OrbitReader2026!')
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page).toHaveURL('/write')
}

test('autosaves and restores a real draft while protecting concurrent edits', async ({ page, context }) => {
  const originalTitle = `钟楼观测记录 ${Date.now()}`
  const originalContent = '午夜十二点，钟楼的指针停在了不存在的第十三格。'
  const serverContent = '另一台设备补充：守夜人的怀表比钟楼慢了七分钟。'
  const localContent = '当前设备尚未保存：阁楼窗边出现了一枚新的鞋印。'

  await test.step('register and let the five-second autosave create the draft', async () => {
    await registerReader(page)

    const publicPosts = await page.request.get('/api/posts?cursor=&size=20')
    expect(publicPosts.ok()).toBe(true)
    await expect(publicPosts.json()).resolves.toMatchObject({ data: [], meta: { hasMore: false } })

    await page.getByLabel('标题').fill(originalTitle)
    await page.getByLabel('正文 Markdown').fill(originalContent)

    await expect(page).toHaveURL(/\/write\/\d+$/, { timeout: 15_000 })
    await expect(page.locator('.editor-toolbar').first()).toContainText('已保存')
  })

  const draftId = Number(new URL(page.url()).pathname.split('/').at(-1))
  expect(draftId).toBeGreaterThan(0)

  await test.step('reload restores the draft through gateway and content storage', async () => {
    await page.reload()
    await expect(page.getByLabel('标题')).toHaveValue(originalTitle)
    await expect(page.getByLabel('正文 Markdown')).toHaveValue(originalContent)
    await expect(page.locator('.editor-toolbar').first()).toContainText('v0')
  })

  const concurrentPage = await context.newPage()
  await test.step('a second browser page advances the optimistic version', async () => {
    await concurrentPage.goto(`/write/${draftId}`)
    await expect(concurrentPage.getByLabel('正文 Markdown')).toHaveValue(originalContent)
    await concurrentPage.getByLabel('正文 Markdown').fill(serverContent)
    await concurrentPage.getByRole('button', { name: '保存草稿' }).click()
    await expect(concurrentPage.locator('.editor-toolbar').first()).toContainText('v1')
    await expect(concurrentPage.getByLabel('正文 Markdown')).toHaveValue(serverContent)
  })

  await test.step('the stale page keeps local text and refuses to overwrite the server', async () => {
    await page.getByLabel('正文 Markdown').fill(localContent)
    await page.getByRole('button', { name: '保存草稿' }).click()

    await expect(page.getByRole('alert')).toContainText('当前编辑内容仍保留')
    await expect(page.getByLabel('正文 Markdown')).toHaveValue(localContent)
    await expect(page.getByRole('button', { name: '提交审核' })).toBeDisabled()
  })

  await test.step('the user can explicitly load the authoritative server version', async () => {
    page.once('dialog', dialog => dialog.accept())
    await page.getByRole('button', { name: '加载服务端版本' }).click()
    await expect(page.getByLabel('正文 Markdown')).toHaveValue(serverContent)
    await expect(page.locator('.editor-toolbar').first()).toContainText('v1')
    await expect(page.getByRole('button', { name: '提交审核' })).toBeEnabled()
  })
})
