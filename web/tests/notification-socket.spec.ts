import { expect, test } from '@playwright/test'

type RefreshSender = () => void

async function authenticateWithNotificationSocket(
  page: import('@playwright/test').Page,
  onSubscribed: (connectFrame: string, sendRefresh: RefreshSender) => void
) {
  await page.context().addCookies([
    { name: 'dc_csrf', value: 'test-csrf', domain: '127.0.0.1', path: '/' }
  ])
  await page.route('**/api/auth/refresh', route => route.fulfill({
    json: {
      code: 'OK', message: 'success',
      data: {
        accessToken: 'access-token',
        expiresAt: '2099-01-01T00:00:00Z',
        user: { userId: 2, displayName: '观测者', roles: ['USER'] }
      }
    }
  }))
  let connectFrame = ''
  let messageSequence = 0
  await page.routeWebSocket('**/ws/notifications', socket => {
    socket.onMessage(message => {
      const frame = typeof message === 'string' ? message : new TextDecoder().decode(message)
      if (frame.startsWith('CONNECT')) {
        connectFrame = frame
        socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
      }
      if (frame.startsWith('SUBSCRIBE')) {
        const subscriptionId = frame.match(/(?:^|\n)id:([^\n\r]+)/)?.[1] ?? 'sub-0'
        onSubscribed(connectFrame, () => {
          messageSequence += 1
          socket.send(
            `MESSAGE\nsubscription:${subscriptionId}\nmessage-id:refresh-${messageSequence}\ndestination:/user/queue/notifications\ncontent-type:application/json\n\n{"type":"REFRESH"}\0`
          )
        })
      }
    })
  })
}

function notification(id: number, read = false) {
  return {
    id,
    type: 'SYSTEM',
    title: `信号 ${id}`,
    content: '轨道状态刷新',
    read,
    targetRoute: null,
    createdAt: '2026-08-12T00:00:00Z'
  }
}

test('enabled notification socket authenticates in STOMP CONNECT and refreshes REST state', async ({ page }) => {
  let connectFrame = ''
  let sendRefresh: RefreshSender | undefined
  let inboxRequests = 0
  let serverUnread = 1
  await authenticateWithNotificationSocket(page, (frame, send) => {
    connectFrame = frame
    sendRefresh = send
  })
  await page.route('**/api/notifications/unread-count', route => route.fulfill({
    json: { code: 'OK', message: 'success', data: serverUnread }
  }))
  await page.route('**/api/notifications?*', route => {
    inboxRequests += 1
    return route.fulfill({
      json: {
        code: 'OK', message: 'success', data: [notification(serverUnread)],
        meta: { nextCursor: '', hasMore: false }
      }
    })
  })

  await page.goto('/notifications')
  await expect.poll(() => sendRefresh).toBeDefined()
  expect(connectFrame).toContain('Authorization:Bearer access-token')
  expect(connectFrame).not.toContain('dc_csrf')
  expect(connectFrame).not.toContain('localStorage')
  const beforeRefresh = inboxRequests
  serverUnread = 2
  sendRefresh?.()

  await expect(page.getByText('2 条未读')).toBeVisible()
  await expect(page.getByRole('button', { name: /信号 2/ })).toBeVisible()
  expect(inboxRequests).toBeGreaterThan(beforeRefresh)
})

test('enabled socket coalesces in-flight signals and ignores an older REST response', async ({ page }) => {
  let sendRefresh: RefreshSender | undefined
  let inboxRequests = 0
  let unreadRequests = 0
  let releaseInitialInbox: () => void = () => undefined
  let releaseInitialUnread: () => void = () => undefined
  let releaseRefreshInbox: () => void = () => undefined
  let releaseRefreshUnread: () => void = () => undefined
  const initialInboxBlocked = new Promise<void>(resolve => { releaseInitialInbox = resolve })
  const initialUnreadBlocked = new Promise<void>(resolve => { releaseInitialUnread = resolve })
  const refreshInboxBlocked = new Promise<void>(resolve => { releaseRefreshInbox = resolve })
  const refreshUnreadBlocked = new Promise<void>(resolve => { releaseRefreshUnread = resolve })
  await authenticateWithNotificationSocket(page, (_frame, send) => { sendRefresh = send })
  await page.route('**/api/notifications/unread-count', async route => {
    const version = ++unreadRequests
    if (version === 1) await initialUnreadBlocked
    if (version === 2) await refreshUnreadBlocked
    await route.fulfill({ json: { code: 'OK', message: 'success', data: version } })
  })
  await page.route('**/api/notifications?*', async route => {
    const version = ++inboxRequests
    if (version === 1) await initialInboxBlocked
    if (version === 2) await refreshInboxBlocked
    await route.fulfill({
      json: {
        code: 'OK', message: 'success', data: [notification(version)],
        meta: { nextCursor: '', hasMore: false }
      }
    })
  })

  await page.goto('/notifications')
  await expect.poll(() => sendRefresh).toBeDefined()
  await expect.poll(() => inboxRequests).toBe(1)
  await expect.poll(() => unreadRequests).toBe(1)
  sendRefresh?.()
  sendRefresh?.()

  await expect.poll(() => inboxRequests).toBe(2)
  await expect.poll(() => unreadRequests).toBe(2)
  releaseRefreshInbox()
  releaseRefreshUnread()
  await expect.poll(() => inboxRequests).toBe(3)
  await expect.poll(() => unreadRequests).toBe(3)
  await expect(page.getByText('3 条未读')).toBeVisible()
  await expect(page.getByRole('button', { name: /信号 3/ })).toBeVisible()

  releaseInitialInbox()
  releaseInitialUnread()
  await expect(page.getByText('3 条未读')).toBeVisible()
  await expect(page.getByRole('button', { name: /信号 1/ })).toHaveCount(0)
})

test('mutation wins over an older snapshot and rapid socket signals cause one post-write refresh', async ({ page }) => {
  let sendRefresh: RefreshSender | undefined
  let inboxRequests = 0
  let unreadRequests = 0
  let mutationRequests = 0
  let serverUnread = 1
  let releaseStaleInbox: () => void = () => undefined
  let releaseStaleUnread: () => void = () => undefined
  let releaseMutation: () => void = () => undefined
  const staleInboxBlocked = new Promise<void>(resolve => { releaseStaleInbox = resolve })
  const staleUnreadBlocked = new Promise<void>(resolve => { releaseStaleUnread = resolve })
  const mutationBlocked = new Promise<void>(resolve => { releaseMutation = resolve })
  await authenticateWithNotificationSocket(page, (_frame, send) => { sendRefresh = send })
  await page.route('**/api/notifications/read-all', async route => {
    mutationRequests += 1
    await mutationBlocked
    serverUnread = 0
    await route.fulfill({
      json: { code: 'OK', message: 'success', data: { updated: 1 } }
    })
  })
  await page.route('**/api/notifications/unread-count', async route => {
    const version = ++unreadRequests
    if (version === 2) await staleUnreadBlocked
    const value = version === 2 ? 1 : serverUnread
    await route.fulfill({ json: { code: 'OK', message: 'success', data: value } })
  })
  await page.route('**/api/notifications?*', async route => {
    const version = ++inboxRequests
    if (version === 2) await staleInboxBlocked
    const read = version === 2 ? false : serverUnread === 0
    await route.fulfill({
      json: {
        code: 'OK', message: 'success', data: [notification(1, read)],
        meta: { nextCursor: '', hasMore: false }
      }
    })
  })

  await page.goto('/notifications')
  await expect.poll(() => sendRefresh).toBeDefined()
  await expect(page.getByText('1 条未读')).toBeVisible()

  // Start a stale read before the write, then keep it unresolved until after the mutation.
  sendRefresh?.()
  await expect.poll(() => inboxRequests).toBe(2)
  await expect.poll(() => unreadRequests).toBe(2)
  await page.getByRole('button', { name: '全部已读' }).click()
  await expect.poll(() => mutationRequests).toBe(1)
  sendRefresh?.()
  sendRefresh?.()
  releaseMutation()
  await expect(page.getByText('0 条未读')).toBeVisible()

  // The stale v2 snapshot finishes last, but its revision has already been invalidated. The
  // coalesced signal then forces an authoritative v3 read after the mutation.
  releaseStaleInbox()
  releaseStaleUnread()
  await expect.poll(() => inboxRequests).toBe(3)
  await expect.poll(() => unreadRequests).toBe(3)
  await expect(page.getByText('0 条未读')).toBeVisible()
  await expect(page.getByRole('button', { name: '全部已读' })).toHaveCount(0)
  expect(inboxRequests).toBe(3)
  expect(unreadRequests).toBe(3)
})
