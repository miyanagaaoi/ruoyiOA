/**
 * ================================================================================
 *  bind-flow-to-template.js —— 把「已经在简易版流程设计器里做好的流程」绑到一个可发起的模板上
 * --------------------------------------------------------------------------------
 *  为什么需要它（本项目的一个真实死角）：
 *
 *    「新启流程」页（/my/newstart）的数据源是 **t_template**：
 *      TemplateMapper.xml#selectNewStartTemplateList
 *        → where del_flag='0' and enable_flag='1'（再过一遍可发起范围）
 *    它与 **t_flow_simple** 之间**没有任何关联**。
 *
 *    而「流程设计（简化版）」是**独立页面**（菜单 9F2C…A1 → workflow/simple-flow/index）。
 *    从那里发布流程只会写 t_flow_simple + 部署 Flowable；SimpleFlowServiceImpl.publish
 *    在没有 templateId 时按 simple_flow_id 反向查模板（getTemplateIdBySimpleFlowId），
 *    独立新建的流程没有任何模板指向它 ⇒ 查不到 ⇒ **静默跳过绑定**（publish:247-252），
 *    界面照样提示「发布成功」，但发起页永远不会有它。
 *
 *    2.0 B1 设计的正路是**内嵌**：模板配置 → 新增模板 → 「流程设计」页签
 *    （getOrCreateByTemplate 派生 defKey = tpl_ + 模板ID前8位）→ 发布时回写
 *    t_template.def_key / simple_flow_id / flow_mode（saveFlowBinding）。
 *    但产品**没有**任何「把已有独立流程绑到模板」的入口 —— 这个脚本补的就是这一刀。
 *
 *  做法：
 *    t_template 的 insertTemplate 支持 def_key / simple_flow_id / flow_mode 三列，
 *    所以直接调产品自己的新增接口（POST /template/template）精确构造出
 *    saveFlowBinding 本该写出的状态。**不直接写 SQL**，不绕过产品校验。
 *
 *  幂等：已存在 del_flag='0' 且 def_key 相同的模板时跳过创建。
 *  安全性：只新增 1 行 t_template，不创建任何子表行；不改动流程定义本身。
 *         （实测：只动 t_template，t_template_submit_scope / _flow_admin / _print_template
 *           等 8 张子表均为 0 行新增。）
 *
 *  用法（在仓库根目录；前置：start-env.ps1 已起 MySQL/Redis/后端 8080）：
 *    node tools/bind-flow-to-template.js --def-key htsp --name 合同审批 \\
 *         --form-id 564FA25A28D340A48DFD39CFE2432AE5 --remark "合同审批（含用印）"
 *
 *    可选参数：
 *      --type-id <id>   模板分类（默认取 t_template_type 里第一条启用行）
 *      --sort <n>       排序（默认 0，越小越靠前）
 *      --scope <0|1|2|3> 可发起范围（0=全部，默认 0）
 *      --account <用户名>（默认 superAdmin）  --password <口令>（默认 admin123）
 *      --base-url <url>  （默认 http://localhost:8080）
 *      --dry-run        只打印将要提交的内容，不真正创建
 *
 *  验证（脚本自带）：/newStart 能查到该模板；/workflow/simple-flow/by-template/{id}
 *    返回的必须是**原来那条流程**（若返回 defKey 变成 tpl_ 前缀，说明绑定没生效、
 *    设计器会另建草稿）。
 *
 *  @author 二开
 * ================================================================================
 */
'use strict';

const net = require('net');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');

/* ---------------- 参数 ---------------- */
function parseArgs(argv) {
  const out = { scope: '0', sort: 0, account: 'superAdmin', password: 'admin123', baseUrl: 'http://localhost:8080', dryRun: false };
  const alias = { 'def-key': 'defKey', name: 'name', 'form-id': 'formId', 'type-id': 'typeId', remark: 'remark', sort: 'sort', scope: 'scope', account: 'account', password: 'password', 'base-url': 'baseUrl' };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dry-run') { out.dryRun = true; continue; }
    const m = a.match(/^--(.+?)(?:=(.*))?$/);
    if (!m) continue;
    const key = alias[m[1]];
    if (!key) continue;
    const val = m[2] !== undefined ? m[2] : argv[++i];
    out[key] = key === 'sort' ? Number(val) : val;
  }
  for (const req of ['defKey', 'name', 'formId']) {
    if (!out[req]) { console.error(`缺少必需参数：--${req === 'defKey' ? 'def-key' : req === 'formId' ? 'form-id' : req}`); process.exit(2); }
  }
  return out;
}

/* ---------------- Redis：读验证码答案 ---------------- */
// 注意：Redis 是长连接，不会主动关连接 —— 必须按 RESP 长度前缀解析，不能等 'end'
function redisGet(key, { host = '127.0.0.1', port = 6379, timeout = 3000 } = {}) {
  return new Promise((resolve, reject) => {
    const s = net.connect(port, host);
    let buf = Buffer.alloc(0);
    let done = false;
    const finish = (v) => { if (!done) { done = true; s.destroy(); resolve(v); } };
    s.setTimeout(timeout, () => { if (!done) { done = true; s.destroy(); reject(new Error('Redis 读超时（6379 起了吗？）')); } });
    s.on('error', (e) => { if (!done) { done = true; reject(e); } });
    s.on('connect', () => s.write(`*2\r\n$3\r\nGET\r\n$${Buffer.byteLength(key)}\r\n${key}\r\n`));
    s.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
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
// RuoYi 的 RedisTemplate 值序列化器是 FastJson —— String 会带 JSON 引号存进去（"y422"）
const unquote = (v) => (v && v.length >= 2 && v.startsWith('"') && v.endsWith('"')) ? JSON.parse(v) : v;

/* ---------------- RSA：复用项目既有工具，避免公钥漂移 ---------------- */
function rsaEncrypt(txt) {
  return execFileSync(process.execPath, [path.join(ROOT, 'tools', 'rsa-encrypt.js'), txt], { encoding: 'utf8' }).trim();
}

/* ---------------- HTTP ---------------- */
async function api(baseUrl, pathname, { method = 'GET', token, body } = {}) {
  const res = await fetch(baseUrl + pathname, {
    method,
    headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: 'Bearer ' + token } : {}),
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  try { return JSON.parse(text); } catch { throw new Error(`非 JSON 响应 ${res.status}：${text.slice(0, 200)}`); }
}

async function login(baseUrl, account, password) {
  const cap = await api(baseUrl, '/captchaImage');
  const code = unquote(await redisGet('captcha_codes:' + cap.uuid));
  if (!code) throw new Error('没能从 Redis 取到验证码答案');
  const res = await api(baseUrl, '/login', {
    method: 'POST',
    body: { username: account, password: rsaEncrypt(password), code, uuid: cap.uuid },
  });
  if (res.code !== 200) throw new Error('登录失败：' + JSON.stringify(res));
  return res.token;
}

(async () => {
  const opt = parseArgs(process.argv);
  const U = opt.baseUrl;
  const token = await login(U, opt.account, opt.password);
  console.log('[1] 登录成功：' + opt.account);

  // 2) 按 def_key 找流程（取未删除的那条）
  const list = await api(U, `/workflow/simple-flow/list?pageNum=1&pageSize=200&defKey=${encodeURIComponent(opt.defKey)}`, { token });
  const flows = (list.rows || []).filter(f => f.defKey === opt.defKey && f.delFlag === '0');
  if (!flows.length) throw new Error(`没找到未删除的流程 def_key=${opt.defKey}（先在「流程设计（简化版）」里做好并发布）`);
  const flow = flows.sort((a, b) => (b.version || 0) - (a.version || 0))[0];
  console.log(`[2] 找到流程：id=${flow.id}  defKey=${flow.defKey}  status=${flow.status}  version=${flow.version}  procDefId=${flow.procDefId}`);
  if (flow.status !== '1') console.warn('    [!] 该流程 status≠1（未发布），发起时可能失败');

  // 3) 幂等：是否已有模板绑定它
  const existing = await api(U, '/template/template/list?pageNum=1&pageSize=200', { token });
  const bound = (existing.rows || []).filter(r => r.defKey === opt.defKey && r.delFlag === '0');
  if (bound.length) {
    console.log('[3] 已存在绑定该流程的模板，跳过创建：');
    bound.forEach(r => console.log(`    id=${r.id}  name=${r.name}  enable=${r.enableFlag}`));
    console.log('[4] 在新启流程中的可见性：' + await visible(U, token, opt.defKey));
    return;
  }

  // 4) 分类：默认取第一条启用的模板分类
  let typeId = opt.typeId;
  if (!typeId) {
    const types = await api(U, '/template/type/listEnable', { token });
    const first = (types.data || [])[0];
    if (!first) throw new Error('没有可用的模板分类（t_template_type），请用 --type-id 指定');
    typeId = first.id;
    console.log(`[4] 使用模板分类：${first.name}（${typeId}）`);
  }

  const payload = {
    name: opt.name,
    type: typeId,
    defKey: flow.defKey,           // ← 关键：显式指定，saveTemplate 只在为空时才派生 tpl_ 前缀
    simpleFlowId: flow.id,         // ← 关键：让「流程设计」页签载入这条既有流程，而不是另建草稿
    flowMode: '0',                 // FLOW_MODE_SIMPLE
    formId: opt.formId,
    formType: '1',
    formCode: 'dynamic',
    submitScopeType: opt.scope,
    includeChildDept: '1',
    enableFlag: '1',
    delFlag: '0',
    sort: opt.sort,
    mainTextFlag: '0',
    attachFlag: '0',
    messageNoticeFlag: '0',
    builtinPrintKey: 'contract',
    remark: opt.remark || '',
  };

  if (opt.dryRun) {
    console.log('[5] --dry-run，将要提交：\n' + JSON.stringify(payload, null, 2));
    return;
  }

  const created = await api(U, '/template/template', { method: 'POST', token, body: payload });
  console.log('[5] 新增模板：' + JSON.stringify(created));
  if (created.code !== 200) throw new Error('新增模板失败');

  // 6) 验证可见性 + 绑定一致性
  const start = await api(U, '/template/template/newStart', { token });
  const flat = (start.data || []).flatMap(g => g.templates || []);
  const hit = flat.find(t => t.defKey === opt.defKey);
  if (!hit) throw new Error('新增成功但 /newStart 仍查不到它');
  console.log(`[6] ✓ 已出现在新启流程：id=${hit.id}  name=${hit.name}`);

  const byTpl = await api(U, `/workflow/simple-flow/by-template/${hit.id}`, { token });
  const got = byTpl.data || {};
  if (got.id !== flow.id) {
    throw new Error(`绑定不一致：流程设计页签会载入 ${got.id}（defKey=${got.defKey}），而不是 ${flow.id}。`
      + ' 模板会被另建 tpl_ 草稿，请检查 simple_flow_id 是否写入。');
  }
  console.log(`[6] ✓ 流程设计页签复用既有流程：${got.defKey}（id=${got.id}），未另建草稿`);
})().catch(e => { console.error('失败：' + e.message); process.exit(1); });

async function visible(U, token, defKey) {
  const start = await api(U, '/template/template/newStart', { token });
  const flat = (start.data || []).flatMap(g => g.templates || []);
  const hit = flat.find(t => t.defKey === defKey);
  return hit ? `✓ 可见（id=${hit.id}）` : '✗ 不可见';
}
