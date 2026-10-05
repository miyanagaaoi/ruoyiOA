/**
 * setup 项目：为每个账号各登录一次并落 storageState。
 *
 * 这样业务用例不必重复登录（登录态含真实 token，落在 .auth/，已 gitignore）。
 */
'use strict';

const { test: setup, expect } = require('@playwright/test');
const { USERS } = require('./helpers/env');
const { loginViaUi, saveState } = require('./helpers/login');

for (const [name, cfg] of Object.entries(USERS)) {
  setup(`登录并保存登录态：${name}`, async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await loginViaUi(page, name, cfg.password);
      const file = await saveState(context, name);
      console.log(`[setup] ${name} 登录成功 → ${file}`);
      expect(page.url()).not.toContain('login');
    } finally {
      await context.close();
    }
  });
}
