import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e-demo',
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 45_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium-real-local-stack', use: { ...devices['Desktop Chrome'] } }],
});
