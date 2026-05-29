import http from 'k6/http'
import { check, sleep } from 'k6'

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '1m'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<1200']
  }
}

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080'
const auth = { Authorization: __ENV.TOKEN || 'Bearer demo-access-token-admin' }

export default function () {
  const feed = http.get(`${baseUrl}/api/recommend/feed?scene=mixed&size=12`, { headers: auth })
  check(feed, { 'feed ok': (r) => r.status === 200 })

  const search = http.get(`${baseUrl}/api/search/posts?keyword=${encodeURIComponent('钟楼')}`, { headers: auth })
  check(search, { 'search ok': (r) => r.status === 200 })

  const detail = http.get(`${baseUrl}/api/posts/1`, { headers: auth })
  check(detail, { 'detail ok': (r) => r.status === 200 || r.status === 404 })

  const like = http.post(`${baseUrl}/api/posts/1/like`, null, {
    headers: { ...auth, 'Idempotency-Key': `k6-like-${__VU}-${__ITER}` }
  })
  check(like, { 'like ok': (r) => r.status === 200 })

  const answer = http.post(`${baseUrl}/api/deduction/cases/1/answers`, JSON.stringify({ answer: '守夜人在钟楼夹层' }), {
    headers: { ...auth, 'Content-Type': 'application/json' }
  })
  check(answer, { 'answer ok': (r) => r.status === 200 })

  sleep(1)
}
