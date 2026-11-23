import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  use: {
    baseURL: process.env.LEETGRIND_TEST_URL ?? 'http://127.0.0.1:5173',
    launchOptions: process.env.LEETGRIND_CHROMIUM_PATH
      ? { executablePath: process.env.LEETGRIND_CHROMIUM_PATH }
      : undefined,
  },
})
