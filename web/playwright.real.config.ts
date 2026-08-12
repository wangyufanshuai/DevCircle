import { defineConfig, devices } from '@playwright/test'

const port = Number(process.env.PLAYWRIGHT_REAL_PORT ?? 5511)
const baseURL = `http://127.0.0.1:${port}`
const apiProxy = process.env.REAL_API_URL ?? 'http://127.0.0.1:28080'

export default defineConfig({
  testDir: './tests-real',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['line'], ['junit', { outputFile: 'test-results/real-auth.xml' }]] : 'line',
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: 60_000,
    env: { VITE_API_PROXY: apiProxy, VITE_NOTIFICATION_SOCKET_ENABLED: 'true' }
  },
  projects: [
    { name: 'real-auth-chromium', use: { ...devices['Desktop Chrome'], channel: 'chromium', viewport: { width: 1440, height: 1000 } } }
  ]
})
