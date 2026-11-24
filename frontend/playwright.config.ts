import { defineConfig, devices } from '@playwright/test';

// End-to-end tests against the real platform (docs/testing.md): run them with scripts/e2e.sh, which provides the
// database, the jars and the environment. They run one at a time because one test restarts the backend.
export default defineConfig({
  testDir: './e2e',
  testMatch: '*.spec.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 30_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  use: {
    baseURL: `http://127.0.0.1:${process.env.E2E_HTTP_PORT ?? 18080}`,
    trace: 'off',
    screenshot: 'only-on-failure',
    acceptDownloads: true,
    ...devices['Desktop Chrome'],
  },
  outputDir: 'test-results',
});
