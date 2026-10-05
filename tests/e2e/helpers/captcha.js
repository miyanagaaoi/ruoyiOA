/**
 * 从本机 Redis 读图形验证码答案 —— 本项目自动登录的关键。
 *
 * 为什么不用 tools/oa-login.ps1 的做法（临时把 sys.account.captchaEnabled 置 false 再恢复）：
 *   那会**改动共享运行配置**，运行期间真实用户看不到验证码，并发跑还会互相踩。
 *   而验证码答案本来就存在本机 Redis 的 `captcha_codes:<uuid>` 里（无鉴权、无前缀）。
 *
 * 两个必须处理的坑（都实测踩过）：
 *   1) RuoYi 的 RedisTemplate 值序列化器是 FastJson —— String 存进去**带 JSON 引号**（"y422"），
 *      不剥引号直接填表必然报「验证码错误」。
 *   2) Redis 是长连接，回包后**不关连接** —— 必须按 RESP 长度前缀解析，等 'end' 会稳定超时。
 */
'use strict';

const net = require('net');
const { REDIS } = require('./env');

function redisGet(key, { host = REDIS.host, port = REDIS.port, timeout = 3000 } = {}) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, host);
    let buf = Buffer.alloc(0);
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; socket.destroy(); resolve(v); } };

    socket.setTimeout(timeout, () => {
      if (!settled) { settled = true; socket.destroy(); reject(new Error(`Redis 读超时（${host}:${port} 起了吗？）`)); }
    });
    socket.on('error', (e) => { if (!settled) { settled = true; reject(e); } });
    socket.on('connect', () => socket.write(`*2\r\n$3\r\nGET\r\n$${Buffer.byteLength(key)}\r\n${key}\r\n`));
    socket.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      const head = buf.indexOf('\r\n');
      if (head < 0) return;
      const line = buf.subarray(0, head).toString('latin1');
      if (line === '$-1' || !line.startsWith('$')) return finish(null);
      const len = Number(line.slice(1));
      const start = head + 2;
      if (buf.length >= start + len) finish(buf.subarray(start, start + len).toString('utf8'));
    });
  });
}

/** 剥掉 FastJson 给 String 加的 JSON 引号 */
function unquote(v) {
  if (v && v.length >= 2 && v.startsWith('"') && v.endsWith('"')) {
    try { return JSON.parse(v); } catch (e) { return v.slice(1, -1); }
  }
  return v;
}

/** 取某个 uuid 对应的验证码答案（已剥引号） */
async function captchaCode(uuid) {
  return unquote(await redisGet('captcha_codes:' + uuid));
}

module.exports = { redisGet, unquote, captchaCode };
