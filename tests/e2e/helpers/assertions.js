/**
 * 断言口径的**唯一定义处**：区分「业务失败」与「已知环境噪声」。
 *
 * 已知噪声：VUE_APP_WS_URL = ws://localhost:8544/im，而 start-env.ps1 **不启动 8544**，
 * 所以每个页面控制台都会有一条 WebSocket 连接失败。它必须被排除，
 * 否则整个套件假红；但白名单只允许匹配「IM 的 WS 连接失败」这一个条件，
 * 不得放大到掩盖真实故障（IM 自身的回归由上层专项覆盖）。
 */
'use strict';

/** 环境噪声白名单：URL 命中即忽略（仅 IM 的 WebSocket） */
const NOISE_URL = [/\/im\?Authorization=/];

function isNoise(url) {
  return NOISE_URL.some((re) => re.test(url));
}

/**
 * 挂上监听并返回两个收集器。用例里不要再自建白名单。
 * @returns {{consoleErrors: string[], apiFailures: string[]}}
 */
function watchPage(page) {
  const consoleErrors = [];
  const apiFailures = [];

  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    if (isNoise(text)) return;
    consoleErrors.push(text);
  });

  page.on('response', (res) => {
    const url = res.url();
    if (!url.includes('/dev-api/')) return; // 只管后端接口
    if (isNoise(url)) return;
    if (res.status() >= 400) apiFailures.push(`${res.status()} ${url}`);
  });

  return { consoleErrors, apiFailures };
}

/** 断言：本次操作没有业务失败（接口 4xx/5xx + 未命中白名单的控制台 error） */
function assertNoBusinessFailure(watch, context) {
  if (watch.apiFailures.length) {
    throw new Error(`[${context}] 出现业务接口失败：\n  ` + watch.apiFailures.join('\n  '));
  }
  if (watch.consoleErrors.length) {
    throw new Error(`[${context}] 控制台错误（非 IM 噪声）：\n  ` + watch.consoleErrors.join('\n  '));
  }
}

module.exports = { NOISE_URL, isNoise, watchPage, assertNoBusinessFailure };
