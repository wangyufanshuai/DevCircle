import http from 'k6/http'
import { check, sleep } from 'k6'

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '1m'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<300'],
    'http_req_duration{kind:write}': ['p(95)<600']
  }
}

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080'
if (!__ENV.TOKEN) throw new Error('TOKEN must contain a real access token')
const auth = { Authorization: __ENV.TOKEN }

export default function () {
  const feed = http.get(`${baseUrl}/api/recommend/feed?scene=mixed&size=12`, { headers: auth, tags: { kind: 'read' } })
  check(feed, { 'feed ok': (r) => r.status === 200 })

  const search = http.get(`${baseUrl}/api/search/posts?keyword=${encodeURIComponent('钟楼')}`, { headers: auth, tags: { kind: 'read' } })
  check(search, { 'search ok': (r) => r.status === 200 })

  const detail = http.get(`${baseUrl}/api/posts/1`, { headers: auth, tags: { kind: 'read' } })
  check(detail, { 'detail ok': (r) => r.status === 200 || r.status === 404 })

  const like = http.post(`${baseUrl}/api/posts/1/like`, null, {
    headers: { ...auth, 'Idempotency-Key': `k6-like-${__VU}-${__ITER}` },
    tags: { kind: 'write' }
  })
  check(like, { 'like ok': (r) => r.status === 200 })

  const answer = http.post(`${baseUrl}/api/deduction/cases/1/answers`, JSON.stringify({ answer: '守夜人在钟楼夹层' }), {
    headers: { ...auth, 'Content-Type': 'application/json', 'Idempotency-Key': `k6-answer-${__VU}-${__ITER}` },
    tags: { kind: 'write' }
  })
  check(answer, { 'answer ok': (r) => r.status === 200 })

  sleep(1)
}
