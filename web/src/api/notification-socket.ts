import { Client } from '@stomp/stompjs'
import { apiBaseUrl, currentAccessToken } from './client'

const MAX_CONSECUTIVE_RECONNECTS = 4

function brokerUrl(): string {
  const base = new URL(apiBaseUrl() || window.location.origin, window.location.origin)
  const url = new URL('/ws/notifications', base)
  if (url.protocol === 'https:') url.protocol = 'wss:'
  else if (url.protocol === 'http:') url.protocol = 'ws:'
  else throw new Error('Unsupported notification transport')
  return url.toString()
}

/**
 * Connects to the authenticated per-user notification queue. Transport failures are intentionally
 * silent because the REST inbox remains authoritative; the socket only triggers a refresh.
 */
export function connectNotificationSocket(onRefresh: () => void): () => void {
  if (!currentAccessToken()) return () => undefined

  let stopped = false
  let consecutiveFailures = 0
  const client = new Client({
    brokerURL: brokerUrl(),
    connectionTimeout: 5_000,
    reconnectDelay: 2_500,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    debug: () => undefined,
    beforeConnect: () => {
      const token = currentAccessToken()
      if (!token) {
        stopped = true
        client.reconnectDelay = 0
        client.connectHeaders = {}
        return
      }
      client.connectHeaders = { Authorization: `Bearer ${token}` }
    },
    onConnect: () => {
      consecutiveFailures = 0
      client.subscribe('/user/queue/notifications', message => {
        try {
          const signal = JSON.parse(message.body) as { type?: unknown }
          if (signal.type === 'REFRESH') onRefresh()
        } catch {
          // Ignore malformed transport hints. The next REST refresh remains authoritative.
        }
      })
    },
    onStompError: () => undefined,
    onWebSocketError: () => undefined,
    onWebSocketClose: () => {
      if (stopped) return
      consecutiveFailures += 1
      if (consecutiveFailures >= MAX_CONSECUTIVE_RECONNECTS) {
        stopped = true
        void client.deactivate()
      }
    }
  })

  client.activate()
  return () => {
    stopped = true
    void client.deactivate()
  }
}
