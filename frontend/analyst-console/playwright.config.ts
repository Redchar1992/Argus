import { defineConfig, devices } from '@playwright/test';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'npm run dev',
      cwd: resolve(here, '../../bff'),
      url: 'http://127.0.0.1:3001/health',
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
      env: {
        ...process.env,
        NODE_ENV: 'test',
        BFF_HOST: '127.0.0.1',
        BFF_PORT: '3001',
        BFF_MOCK_UPSTREAM: 'true',
        BFF_ALLOWED_ORIGINS: 'http://127.0.0.1:5173',
        BFF_COOKIE_SECURE: 'false',
        BFF_LOGGER: 'false',
      },
    },
    {
      command: 'npm run dev -- --host 127.0.0.1',
      cwd: here,
      url: 'http://127.0.0.1:5173',
      reuseExistingServer: !process.env.CI,
      timeout: 30_000,
      env: { ...process.env, VITE_BFF_TARGET: 'http://127.0.0.1:3001' },
    },
  ],
});
