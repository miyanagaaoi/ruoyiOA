/**
 * B4 进销存 E2E 的 **HTTP 层工具**（不依赖页面，可直接打接口）。
 *
 * 为什么单独一层（而不是每条用例自己 fetch）：
 *   1. 认证只有一个口径 —— RuoYi 把 token 放在 cookie `Admin-Token`（`src/utils/auth.js`），
 *      登录态已经由 setup 项目落进 `.auth/<user>.json`，这里只做"取 token + 加 Authorization 头"；
 *   2. **判 success 的口径必须统一且不含糊**（DEV-ENV §6.56 教过我们一次）：
 *      RuoYi 的认证失败是 HTTP 200 + body `{code:401}`，业务异常是 HTTP 200 + body `{code:500}`，
 *      所以断言要**同时**看 HTTP 状态与 body.code，失败时把 body 原文带进断言消息；
 *   3. 列表接口返回 `{code,msg,rows,total}`（TableDataInfo）、其余返回 `{code,msg,data}`（AjaxResult），
 *      两种形状在这里各给一个入口，用例里不再写 `body.data.data` 之类的绕口令。
 *
 * 真源（逐个 grep 过，不是猜的）：
 *   · 端点与权限点：`ruoyi-ctms/**\/erp/**\/controller/*.java`（@RequestMapping + @XxxMapping）
 *   · 字段名：`erp/<域>/domain/*.java` 与 `erp/base/domain/{ErpDocHeader,ErpDocItem}.java`
 *   · 口径与断言清单：`openspec/changes/oa-purchase-sales-stock/notes/{03-posting,04a-procurement,04b-procurement-push-in,05a-sales,06-stockops,07-ledger}.md`
 *
 * @author 二开（t24：t14 前置）
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { expect } = require('@playwright/test');
const { API_URL, AUTH_DIR } = require('./env');

/** 从 setup 落的 storageState 里取后端 token（cookie `Admin-Token`） */
function tokenOf(user) {
  const file = path.join(AUTH_DIR, `${user}.json`);
  if (!fs.existsSync(file)) {
    throw new Error(
      `缺少登录态文件 ${file}\n` +
      `  → 先跑：npx playwright test --project=setup（或 --project=setup --grep 登录）\n` +
      `  → 该文件由 auth.setup.js 走真实登录页生成（含真实 token，.auth/ 已 gitignore）`
    );
  }
  const state = JSON.parse(fs.readFileSync(file, 'utf8'));
  const cookie = (state.cookies || []).find((c) => c.name === 'Admin-Token');
  if (!cookie || !cookie.value) {
    throw new Error(`登录态文件里没有 Admin-Token cookie：${file}`);
  }
  return cookie.value;
}

class Api {
  /**
   * @param {import('@playwright/test').APIRequestContext} request Playwright 的 request fixture
   * @param {string} user 账号名（默认 superAdmin：B4 的权限点默认只有超管有，见 §3.9.5 的已知边界）
   */
  constructor(request, user = 'superAdmin') {
    this.request = request;
    this.user = user;
    this.token = tokenOf(user);
    /** 记录本用例打过的所有请求，失败时能一眼看出"卡在哪一步" */
    this.trace = [];
  }

  url(p) {
    return API_URL + (String(p).startsWith('/') ? p : `/${p}`);
  }

  async call(method, p, { data, params } = {}) {
    const headers = { Authorization: `Bearer ${this.token}` };
    if (data !== undefined) {
      headers['Content-Type'] = 'application/json; charset=utf-8';
    }
    const res = await this.request.fetch(this.url(p), {
      method,
      headers,
      data,
      params,
      failOnStatusCode: false,
    });
    const status = res.status();
    let body;
    const text = await res.text();
    try {
      body = text ? JSON.parse(text) : {};
    } catch (e) {
      body = { code: -1, msg: `非 JSON 响应：${text.slice(0, 200)}` };
    }
    this.trace.push(`${method} ${p} → HTTP ${status} / code ${body.code}`);
    return { status, body, text };
  }

  get(p, params) { return this.call('GET', p, { params }); }
  post(p, data, params) { return this.call('POST', p, { data, params }); }
  put(p, data, params) { return this.call('PUT', p, { data, params }); }
  del(p) { return this.call('DELETE', p); }

  /** 失败时把最近 N 条请求轨迹拼进断言消息（定位"到底哪一步红了"） */
  trail(limit = 12) {
    return this.trace.slice(-limit).join('\n    ');
  }
}

/** 取 request fixture 并建一个带 token 的 Api（用例里最常用的入口） */
function apiFor(request, user = 'superAdmin') {
  return new Api(request, user);
}

/**
 * 不依赖浏览器上下文/`request` fixture 的 Api（供 `test.beforeAll` 用）。
 *
 * 为什么不用 `request` fixture：它是 **test 作用域** 的，`beforeAll` 里拿不到
 * （Playwright 会在 beforeAll 直接报 "Fixture request is not available"）。
 * 这里用 `playwright.request.newContext()` 自建一个，等价且可在 beforeAll/afterAll 里管理。
 *
 * @returns {Promise<{api: Api, dispose: () => Promise<void>}>}
 */
async function createApi(user = 'superAdmin') {
  const { request: pwRequest } = require('@playwright/test');
  const ctx = await pwRequest.newContext();
  return { api: new Api(ctx, user), dispose: () => ctx.dispose() };
}

/* ------------------------------------------------------------------ 断言口径 */

/**
 * 断言"业务成功"并返回 `data`（AjaxResult 形状）。
 * @returns {Promise<any>} body.data
 */
async function okData(api, res, what) {
  expect(res.status, `[${what}] HTTP 状态（HTTP ${res.status}，body=${JSON.stringify(res.body).slice(0, 300)}）`).toBe(200);
  expect(res.body.code, `[${what}] body.code（msg=${res.body.msg}）`).toBe(200);
  return res.body.data;
}

/** 断言"分页列表成功"并返回 `{rows,total}`（TableDataInfo 形状） */
async function okList(api, res, what) {
  expect(res.status, `[${what}] HTTP 状态（body=${JSON.stringify(res.body).slice(0, 300)}）`).toBe(200);
  expect(res.body.code, `[${what}] body.code（msg=${res.body.msg}）`).toBe(200);
  expect(Array.isArray(res.body.rows), `[${what}] 应返回 rows 数组`).toBe(true);
  return { rows: res.body.rows, total: res.body.total };
}

/**
 * 断言"业务失败"并返回 msg。
 *
 * ⚠ 业务失败**不是** HTTP 4xx/5xx：RuoYi 的 ServiceException 走 HTTP 200 + body `{code:500,msg}`；
 * 认证失败是 HTTP 200 + body `{code:401}`（DEV-ENV §6.56）。所以这里既接受"HTTP 非 200"，
 * 也接受"HTTP 200 但 body.code !== 200"，但**必须**有一处是失败的。
 */
async function bizError(api, res, what, msgRe) {
  const httpFailed = res.status >= 400;
  const bizFailed = res.body.code !== 200;
  expect(
    httpFailed || bizFailed,
    `[${what}] 期望业务失败，实际 HTTP ${res.status} / code ${res.body.code}（body=${JSON.stringify(res.body).slice(0, 300)}）`
  ).toBe(true);
  if (msgRe) {
    expect(res.body.msg, `[${what}] 失败文案`).toMatch(msgRe);
  }
  return res.body.msg;
}

/** 断言"未认证/无权"：HTTP 401 或 body.code 401/403（两种形态都见过） */
function isAuthFailure(res) {
  return res.status === 401 || res.body.code === 401 || res.body.code === 403;
}

module.exports = { Api, apiFor, createApi, okData, okList, bizError, isAuthFailure, tokenOf };
