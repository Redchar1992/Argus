import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e-pages',
  fullyParallel: false,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173/Argus/',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium-pages-demo', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'VITE_BASE_PATH=/Argus/ npm run preview -- --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173/Argus/',
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
  },
});
