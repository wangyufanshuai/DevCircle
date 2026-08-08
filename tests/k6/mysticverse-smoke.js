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
const runId = (__ENV.RUN_ID || `${Date.now().toString(36)}-${Math.random().toString(16).slice(2)}`)
  .replace(/[^A-Za-z0-9._-]/g, '-')
  .slice(0, 48)

if (!Number.isInteger(postId) || postId <= 0) {
  throw new Error('POST_ID must identify an existing published deduction post')
}
if (tokens.length === 0) {
  throw new Error('TOKEN or TOKENS must contain at least one real access token')
}
if (!runId) {
  throw new Error('RUN_ID must contain at least one letter, digit, dot, underscore or dash')
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
    'http_req_duration{kind:write}': ['p(95)<600'],
    'http_req_duration{operation:feed}': ['p(95)<300'],
    'http_req_duration{operation:search}': ['p(95)<300'],
    'http_req_duration{operation:detail}': ['p(95)<300'],
    'http_req_duration{operation:like}': ['p(95)<600'],
    'http_req_duration{operation:unlike}': ['p(95)<600'],
    'http_req_duration{operation:answer}': ['p(95)<600'],
    'http_req_failed{operation:feed}': ['rate<0.01'],
    'http_req_failed{operation:search}': ['rate<0.01'],
    'http_req_failed{operation:detail}': ['rate<0.01'],
    'http_req_failed{operation:like}': ['rate<0.01'],
    'http_req_failed{operation:unlike}': ['rate<0.01'],
    'http_req_failed{operation:answer}': ['rate<0.01']
  }
}

function authorizationHeaders(vu = __VU) {
  const token = tokens[Math.max(0, vu - 1) % tokens.length]
  const clientOctet = (Math.max(1, vu) - 1) % 200 + 1
  return {
    Authorization: token.startsWith('Bearer ') ? token : `Bearer ${token}`,
    'X-Forwarded-For': `198.51.100.${clientOctet}`
  }
}

function verify(response, name, failureRate) {
  const ok = check(response, { [`${name} returns 200`]: (result) => result.status === 200 })
  failureRate.add(!ok)
  return ok
}

export function setup() {
  const response = http.get(`${baseUrl}/api/posts/${postId}`, {
    headers: authorizationHeaders(1),
    tags: { kind: 'setup', operation: 'setup-detail' }
  })
  if (response.status !== 200) {
    throw new Error(`POST_ID preflight failed with HTTP ${response.status}`)
  }
  let body
  try {
    body = response.json()
  } catch {
    throw new Error('POST_ID preflight did not return JSON')
  }
  if (body?.data?.status !== 'PUBLISHED' || body?.data?.solvable !== true) {
    throw new Error('POST_ID must identify a published solvable post')
  }
  return { postId }
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
  sleep(1)
}

export function writeFlow() {
  const headers = authorizationHeaders()
  const requestId = `${runId}-${__VU}-${__ITER}`
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
  sleep(3)
}
