import { expect, test, type APIRequestContext } from '@playwright/test'

type Session = { userId: number; token: string; displayName: string }

async function register(request: APIRequestContext, label: string): Promise<Session> {
  const unique = `${Date.now()}-${Math.random().toString(16).slice(2)}`
  const displayName = `${label}-${unique.slice(-6)}`
  const response = await request.post('/api/auth/register', {
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    data: { email: `${label}-${unique}@example.test`, password: 'OrbitReader2026!', displayName }
  })
  expect(response.status()).toBe(200)
  const body = await response.json()
  expect(body).toMatchObject({ code: 'OK', data: { user: { displayName } } })
  return { userId: body.data.user.userId, token: body.data.accessToken, displayName }
}

function auth(session: Session) {
  return { Authorization: `Bearer ${session.token}` }
}

test('synchronizes profiles and delivers exactly one notification for an idempotent follow', async ({ request }) => {
  const follower = await register(request, '真实关注者')
  const author = await register(request, '真实作者')

  await test.step('registration events create both public profiles', async () => {
    await expect.poll(async () => {
      const left = await request.get(`/api/users/${follower.userId}`)
      const right = await request.get(`/api/users/${author.userId}`)
      return [left.status(), right.status()]
    }, { timeout: 20_000 }).toEqual([200, 200])
  })

  const followKey = crypto.randomUUID()
  await test.step('the same follow request changes state only once', async () => {
    const first = await request.post(`/api/users/${author.userId}/follow`, {
      headers: { ...auth(follower), 'Idempotency-Key': followKey }
    })
    expect(first.status()).toBe(200)
    await expect(first.json()).resolves.toMatchObject({
      code: 'OK', data: { targetUserId: author.userId, followed: true, duplicated: false }
    })

    const retry = await request.post(`/api/users/${author.userId}/follow`, {
      headers: { ...auth(follower), 'Idempotency-Key': followKey }
    })
    await expect(retry.json()).resolves.toMatchObject({
      code: 'OK', data: { targetUserId: author.userId, followed: true, duplicated: true }
    })
  })

  await test.step('Kafka delivery creates one notification for the author', async () => {
    await expect.poll(async () => {
      const response = await request.get('/api/notifications?size=20', { headers: auth(author) })
      if (response.status() !== 200) return []
      return (await response.json()).data
    }, { timeout: 20_000 }).toEqual([
      expect.objectContaining({ type: 'FOLLOW', actorId: follower.userId, targetType: 'USER' })
    ])
  })

  await test.step('unfollow is persisted and does not manufacture another notification', async () => {
    const response = await request.delete(`/api/users/${author.userId}/follow`, {
      headers: { ...auth(follower), 'Idempotency-Key': crypto.randomUUID() }
    })
    await expect(response.json()).resolves.toMatchObject({
      code: 'OK', data: { targetUserId: author.userId, followed: false, duplicated: false }
    })

    await expect.poll(async () => {
      const state = await request.get(`/api/users/${author.userId}/follow-state`, { headers: auth(follower) })
      return (await state.json()).data.following
    }).toBe(false)

    await new Promise(resolve => setTimeout(resolve, 1_500))
    const notifications = await request.get('/api/notifications?size=20', { headers: auth(author) })
    expect((await notifications.json()).data).toHaveLength(1)
  })
})
