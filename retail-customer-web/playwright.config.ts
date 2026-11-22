import { defineConfig, devices } from '@playwright/test'

const port = process.env.RETAIL_PORTAL_PORT ?? '5173'
const baseURL = `http://127.0.0.1:${port}`
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  use: { baseURL, trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: { command: 'npm run dev', url: baseURL, reuseExistingServer: false, timeout: 30_000 },
})
