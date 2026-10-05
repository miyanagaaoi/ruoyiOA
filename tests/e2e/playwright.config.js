// ruoyiOA 浏览器端到端回归（独立套件）
//
// 关键取舍见 openspec/changes/oa-e2e-playwright/design.md：
//   · channel: 'chrome' —— 复用本机已装 Chrome，不下载浏览器内核
//   · 不托管 dev server —— 复用 start-env.ps1 已起的 80/8080，避免重复编译与弹窗
//   · setup 项目先登录各账号并存 storageState，业务用例跨账号复用
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1, // 会写业务数据，串行执行
  reporter: [
    ['list'],
    ['html', { outputFolder: 'reports/html', open: 'never' }],
  ],
  outputDir: 'reports/artifacts', // tests/e2e/reports 已在 .gitignore
  use: {
    channel: 'chrome',
    baseURL: 'http://localhost',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    { name: 'setup', testDir: '.', testMatch: /auth\.setup\.js/ },
    { name: 'e2e', testDir: './specs', dependencies: ['setup'] },
  ],
});
