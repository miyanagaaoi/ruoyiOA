/**
 * 环境与账号常量。
 *
 * 默认值对齐 start-env.ps1 起的本地环境（前端 80 / 后端 8080 / Redis 6379）。
 * 需要时用环境变量覆盖，例如指向另一套环境：
 *   $env:E2E_BASE_URL='http://10.0.0.5'; $env:E2E_API_URL='http://10.0.0.5:8080'
 */
'use strict';

const path = require('path');

const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost';
const API_URL = process.env.E2E_API_URL || 'http://localhost:8080';

/** 各账号的口令（与 tools/oa-login.ps1 的默认值一致） */
const USERS = {
  superAdmin: { password: process.env.E2E_PWD_SUPERADMIN || 'admin123' },
  zhangwei: { password: process.env.E2E_PWD_ZHANGWEI || 'admin123' },
};

/** storageState 落盘目录（.auth/ 已在 .gitignore —— 里面是真实 token，绝不能入库） */
const AUTH_DIR = path.join(__dirname, '..', '.auth');
const REDIS = { host: '127.0.0.1', port: 6379 };

/** 合同审批相关标识（与 tools/bind-flow-to-template.js 建出的绑定一致） */
const CONTRACT = {
  templateName: '合同审批',
  defKey: 'htsp',
  /**
   * 全链路使用的账号。
   *
   * ⚠ 2026-10-05 的妥协（方案 c）：`sys_role_menu` 是空表 + `SecurityUtils.isAdmin`
   * 只认 `userName == "superAdmin"`，导致**非超管用户零菜单零权限**
   * （/getRouters 返回 []、/my/todo 404、接口 403）。
   * 而流程首节点原本指派给张伟（普通用户）——他打不开待办，链路走不完。
   * 因此先把首节点受理人改回 superAdmin（见 .cache/set-first-approver.js，流程已发到 v5），
   * 本用例只覆盖超管链路。
   *
   * 待权限模型修好后：把首节点受理人改回真实审批人，并把这里的 ACTOR 换成他，
   * 用例无需改结构即可恢复「多账号跨用户审批」。
   */
  actor: 'superAdmin',
  /** 条件分支命中的节点名（合同类型=经营 ⇒ value 1） */
  expectedBranchNode: '经发部审批',
  /**
   * 合同类型=经营 时，**发起之后**需要逐级审批的节点（按顺序，每一步都要在待办里出现）。
   *
   * ⚠ 不含首节点「发起部门审核」：本系统的「发起」= 拟稿 + 提交，
   *   `startFlow` 创建实例时就会生成首节点任务，而拟稿页的「提交」直接完成该任务
   *   （实测轨迹：n_first 22:08:05.698 开始 → 22:08:10.508 结束，正好是点提交的时刻）。
   *   所以首个**待**审批节点是条件分支命中的「经发部审批」——这同时也是条件值口径的断言点。
   */
  approvalChain: ['经发部审批', '财务', '审批节点', '办理节点'],
};

module.exports = { BASE_URL, API_URL, USERS, AUTH_DIR, REDIS, CONTRACT };
