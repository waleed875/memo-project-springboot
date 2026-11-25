import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';

const reactRoot = resolve(process.cwd(),
  'legacy-reference/Memo_Project_Handover_v2.0.2/02_الكود_المصدري_Source_Code/source/src/MemoDashboard.Web/Frontend');

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 35_000,
  expect: { timeout: 12_000 },
  reporter: [['list'], ['junit', { outputFile: 'results/junit.xml' }]],
  outputDir: 'results/artifacts',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    locale: 'ar-SY',
    timezoneId: 'Asia/Damascus',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --strictPort',
    cwd: reactRoot,
    url: 'http://127.0.0.1:5173/dashboard-browser-parity.html',
    timeout: 90_000,
    reuseExistingServer: false,
  },
});
