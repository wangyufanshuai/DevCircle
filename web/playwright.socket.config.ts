import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.PLAYWRIGHT_SOCKET_PORT ?? 5511)
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './tests',
  testMatch: /notification-socket\.spec\.ts/,
  timeout: 30_000,
  use: {
    baseURL,
    trace: 'on-first-retry'
  },
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${port} --strictPort`,
    url: baseURL,
    env: { VITE_NOTIFICATION_SOCKET_ENABLED: 'true' },
    reuseExistingServer: true,
    timeout: 60_000
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chromium', viewport: { width: 1440, height: 1000 } } },
    { name: 'laptop', use: { ...devices['Desktop Chrome'], channel: 'chromium', viewport: { width: 1024, height: 900 } } },
    { name: 'tablet', use: { ...devices['Desktop Chrome'], channel: 'chromium', viewport: { width: 768, height: 1024 } } },
    { name: 'mobile', use: { ...devices['Pixel 5'], channel: 'chromium', viewport: { width: 390, height: 844 } } }
  ]
})
