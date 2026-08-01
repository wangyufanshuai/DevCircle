import http from 'k6/http'
import { check, sleep } from 'k6'
import { Rate } from 'k6/metrics'

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '')
const postId = Number(__ENV.POST_ID || 0)
const tokens = (__ENV.TOKENS || __ENV.TOKEN || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean)
const searchKeyword = __ENV.SEARCH_KEYWORD || '钟楼'
const answer = __ENV.ANSWER || '无法确定'

if (!Number.isInteger(postId) || postId <= 0) {
  throw new Error('POST_ID must identify an existing published deduction post')
}
if (tokens.length === 0) {
  throw new Error('TOKEN or TOKENS must contain at least one real access token')
}

const readFailures = new Rate('read_failures')
const writeFailures = new Rate('write_failures')

export const options = {
  scenarios: {
    reads: {
      executor: 'constant-vus',
      exec: 'readFlow',
      vus: Number(__ENV.READ_VUS || 40),
      duration: __ENV.DURATION || '3m',
      gracefulStop: '15s'
    },
    writes: {
      executor: 'constant-vus',
      exec: 'writeFlow',
      vus: Number(__ENV.WRITE_VUS || 10),
      duration: __ENV.DURATION || '3m',
      gracefulStop: '15s'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    read_failures: ['rate<0.01'],
    write_failures: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<300'],
    'http_req_duration{kind:write}': ['p(95)<600']
  }
}

function authorizationHeaders() {
  const token = tokens[(__VU - 1) % tokens.length]
  return { Authorization: token.startsWith('Bearer ') ? token : `Bearer ${token}` }
}

function verify(response, name, failureRate) {
  const ok = check(response, { [`${name} returns 200`]: (result) => result.status === 200 })
  failureRate.add(!ok)
  return ok
}

export function readFlow() {
  const headers = authorizationHeaders()
  verify(http.get(`${baseUrl}/api/recommend/feed?scene=mixed&size=12`, {
    headers,
    tags: { kind: 'read', operation: 'feed' }
  }), 'feed', readFailures)
  verify(http.get(`${baseUrl}/api/search/posts?keyword=${encodeURIComponent(searchKeyword)}`, {
    headers,
    tags: { kind: 'read', operation: 'search' }
  }), 'search', readFailures)
  verify(http.get(`${baseUrl}/api/posts/${postId}`, {
    headers,
    tags: { kind: 'read', operation: 'detail' }
  }), 'detail', readFailures)
  sleep(0.5)
}

export function writeFlow() {
  const headers = authorizationHeaders()
  const requestId = `${__VU}-${__ITER}`
  verify(http.post(`${baseUrl}/api/posts/${postId}/like`, null, {
    headers: { ...headers, 'Idempotency-Key': `k6-like-${requestId}` },
    tags: { kind: 'write', operation: 'like' }
  }), 'like', writeFailures)
  verify(http.del(`${baseUrl}/api/posts/${postId}/like`, null, {
    headers: { ...headers, 'Idempotency-Key': `k6-unlike-${requestId}` },
    tags: { kind: 'write', operation: 'unlike' }
  }), 'unlike', writeFailures)
  verify(http.post(`${baseUrl}/api/deduction/cases/${postId}/answers`, JSON.stringify({ answer }), {
    headers: { ...headers, 'Content-Type': 'application/json', 'Idempotency-Key': `k6-answer-${requestId}` },
    tags: { kind: 'write', operation: 'answer' }
  }), 'answer', writeFailures)
  sleep(1)
}
