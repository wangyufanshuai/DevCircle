import { expect, test, type BrowserContext, type Page } from '@playwright/test'

const adminEmail = process.env.REAL_REVIEW_ADMIN_EMAIL ?? 'real-review-admin@example.test'
const adminPassword = process.env.REAL_REVIEW_ADMIN_PASSWORD ?? 'OrbitAdmin2026!'

async function registerCreator(page: Page) {
  const unique = `${Date.now()}-${Math.random().toString(16).slice(2)}`
  await page.goto('/auth?redirect=/write')
  await page.getByRole('group', { name: '认证方式' }).getByRole('button', { name: '注册' }).click()
  await page.getByLabel('显示名称').fill(`真实投稿人${unique.slice(-6)}`)
  await page.getByLabel('邮箱').fill(`real-review-author-${unique}@example.test`)
  await page.getByLabel('密码').fill('OrbitReader2026!')
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page).toHaveURL('/write')
}

async function loginAdministrator(context: BrowserContext) {
  const page = await context.newPage()
  await page.goto('/auth?redirect=/admin')
  await page.getByLabel('邮箱').fill(adminEmail)
  await page.getByLabel('密码').fill(adminPassword)
  const responsePromise = page.waitForResponse(response =>
    response.url().endsWith('/api/auth/login') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '提交登录' }).click()
  const response = await responsePromise
  expect(response.ok()).toBe(true)
  const payload = await response.json()
  await expect(page).toHaveURL('/admin')
  expect(payload.data.user.roles).toContain('ADMIN')
  return { page, accessToken: String(payload.data.accessToken) }
}

test('submits, moderates and publishes content without leaking its solution', async ({ page, browser }) => {
  const title = `第十三格钟楼 ${Date.now()}`
  const markdown = '午夜之后，守夜人发现钟楼多出了一格。\n\n线索隐藏在怀表的七分钟时差里。'
  const answerKeyword = '林默'
  const solution = '林默利用慢七分钟的怀表制造了不在场证明。'

  await test.step('the author submits a real draft for review', async () => {
    await registerCreator(page)
    await page.getByLabel('标题').fill(title)
    await page.getByLabel('正文 Markdown').fill(markdown)
    await page.getByLabel('问题').fill('谁制造了错误时间线？')
    await page.getByLabel('答案关键词').fill(answerKeyword)
    await page.getByLabel('解析').fill(solution)
    await page.getByRole('button', { name: '提交审核' }).click()
    await expect(page.getByText('等待审核', { exact: true })).toBeVisible()
    await expect(page.getByLabel('正文 Markdown')).toBeDisabled()
  })

  const postId = Number(new URL(page.url()).pathname.split('/').at(-1))
  expect(postId).toBeGreaterThan(0)

  await test.step('pending content is unavailable through the public contract', async () => {
    const response = await page.request.get(`/api/posts/${postId}`)
    expect(response.status()).toBe(404)
    await expect(response.json()).resolves.toMatchObject({ code: 'RESOURCE_NOT_FOUND' })
  })

  const adminContext = await browser.newContext()
  const { page: adminPage, accessToken } = await loginAdministrator(adminContext)
  const appOrigin = new URL(adminPage.url()).origin
  const tasksResponse = await adminContext.request.get(`${appOrigin}/api/admin/moderation/tasks?status=PENDING&page=1&size=20`, {
    headers: { Authorization: `Bearer ${accessToken}` }
  })
  expect(tasksResponse.ok()).toBe(true)
  const tasksPayload = await tasksResponse.json()
  const task = tasksPayload.data.find((item: { postId: number }) => item.postId === postId)
  expect(task).toBeTruthy()

  await test.step('both API and UI require a meaningful moderation reason', async () => {
    const invalid = await adminContext.request.post(`${appOrigin}/api/admin/moderation/tasks/${task.taskId}/approve`, {
      headers: { Authorization: `Bearer ${accessToken}`, 'Idempotency-Key': crypto.randomUUID() },
      data: { reason: '   ' }
    })
    expect(invalid.status()).toBe(400)
    await expect(invalid.json()).resolves.toMatchObject({ code: 'VALIDATION_FAILED' })

    const card = adminPage.locator('.moderation-card').filter({ hasText: title })
    await expect(card).toBeVisible()
    await card.getByRole('button', { name: '通过', exact: true }).click()
    await expect(adminPage.getByRole('alert')).toContainText('必须填写理由')
    await expect(card).toBeVisible()
  })

  const decisionReason = '叙事完整、线索可验证，答案保持解锁后可见。'
  let approvalKey = ''
  let approvalUrl = ''
  await test.step('an administrator approves through the real operations page', async () => {
    const card = adminPage.locator('.moderation-card').filter({ hasText: title })
    await card.getByLabel('审核理由').fill(decisionReason)
    const requestPromise = adminPage.waitForRequest(request =>
      request.url().endsWith(`/api/admin/moderation/tasks/${task.taskId}/approve`))
    const responsePromise = adminPage.waitForResponse(response =>
      response.url().endsWith(`/api/admin/moderation/tasks/${task.taskId}/approve`))
    await card.getByRole('button', { name: '通过', exact: true }).click()
    const [request, response] = await Promise.all([requestPromise, responsePromise])
    expect(response.ok()).toBe(true)
    approvalKey = request.headers()['idempotency-key']
    approvalUrl = request.url()
    expect(approvalKey).toBeTruthy()
    await expect(card).toHaveCount(0)
  })

  await test.step('replaying the same decision is idempotent', async () => {
    const replay = await adminContext.request.post(approvalUrl, {
      headers: { Authorization: `Bearer ${accessToken}`, 'Idempotency-Key': approvalKey },
      data: { reason: decisionReason }
    })
    expect(replay.ok()).toBe(true)
    await expect(replay.json()).resolves.toMatchObject({
      data: { action: 'APPROVE_POST', attributes: { duplicateRequest: true } }
    })
  })

  await test.step('published detail exposes markdown but not answer internals', async () => {
    const response = await page.request.get(`/api/posts/${postId}`)
    expect(response.ok()).toBe(true)
    const payload = await response.json()
    expect(payload.data).toMatchObject({
      id: postId,
      title,
      markdown,
      status: 'PUBLISHED',
      deductionState: { solutionUnlocked: false },
      solution: null
    })
    expect(payload.data).not.toHaveProperty('answerKeywords')
    expect(JSON.stringify(payload.data)).not.toContain(answerKeyword)
    expect(JSON.stringify(payload.data)).not.toContain(solution)

    await page.goto(`/write/${postId}`)
    await expect(page.getByText('已发布', { exact: true })).toBeVisible()
    await expect(page.getByText(`审核意见：${decisionReason}`)).toBeVisible()
    await expect(page.getByText('审核通过', { exact: true })).toBeVisible()
  })

  await adminContext.close()
})
