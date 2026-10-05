/**
 * 登录辅助。
 *
 * 走**真实登录页**而不是接口登录，原因：
 *   本项目登录密码是前端 RSA 加密后提交的（src/api/login.js 的 encrypt），
 *   接口脚本必须自己复刻加密（故存在 tools/rsa-encrypt.js）；
 *   走真实页面则加密由应用自己完成，脚本零复刻，且登录页本身也被覆盖到。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { captchaCode } = require('./captcha');

/**
 * 在给定 page 上完成一次真实登录（须是未登录的 context）。
 * @param {import('@playwright/test').Page} page
 * @param {string} username
 * @param {string} password
 */
async function loginViaUi(page, username, password) {
  // 验证码请求由登录页 created() 发出 —— 必须先挂监听再 goto，否则会漏
  const captchaResp = page.waitForResponse((r) => r.url().includes('/captchaImage'), { timeout: 30_000 });

  await page.goto('/');
  const captcha = await (await captchaResp).json();

  await page.getByPlaceholder('账号').fill(username);
  await page.getByPlaceholder('密码').fill(password);

  if (captcha.captchaEnabled) {
    const code = await captchaCode(captcha.uuid);
    if (!code) throw new Error(`未能从 Redis 取到验证码答案（uuid=${captcha.uuid}）`);
    await page.getByPlaceholder('验证码').fill(code);
  }

  await page.getByRole('button', { name: /登\s*录/ }).click();
  await page.waitForURL((url) => !url.pathname.includes('login'), { timeout: 30_000 });
  await page.waitForLoadState('networkidle').catch(() => {});
}

/** 把当前登录态写到 .auth/<name>.json（目录不存在则新建） */
async function saveState(context, name) {
  const { AUTH_DIR } = require('./env');
  fs.mkdirSync(AUTH_DIR, { recursive: true });
  const file = path.join(AUTH_DIR, `${name}.json`);
  await context.storageState({ path: file });
  return file;
}

module.exports = { loginViaUi, saveState };
