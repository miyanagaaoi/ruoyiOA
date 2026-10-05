# 交付与推送执行单（captain 维护 · 用户已定，收尾时执行）

> 用户指令（2026-10-05）：**全部完成后**提交到 `https://github.com/miyanagaaoi/ruoyiOA.git`，**用 SSH 私钥**。
> 用户已选定结构：**「一个仓库装全部」**（选项 A）。
> 前置：本文件是执行单，不是任务；成员不要动它。

## 1. 已核实的环境事实

| 事实 | 证据 |
| --- | --- |
| 目标仓库**存在但完全为空**（无任何 ref） | `git ls-remote git@github.com:miyanagaaoi/ruoyiOA.git` → 空输出 / exit 0 |
| SSH 私钥可用 | `ssh -T git@github.com` → `Hi miyanagaaoi! You've successfully authenticated...` |
| 认证路径 | `~/.ssh/config`：`github.com` → `ssh.github.com:443`，`IdentityFile ~/.ssh/id_ed25519`，`ProxyCommand connect.exe -H 127.0.0.1:7899`（代理 7899 实测在线） |
| 三个仓库**都没有配置 remote** | `git remote -v` 三仓均为空 |
| 三仓分支均为 `master` | 外层 135 文件 / 后端 1291 / 前端 730（tracked） |

## 2. 推送结构（用户已选）

| 远端分支 | 内容 | 目的 |
| --- | --- | --- |
| `master` | **monorepo 快照**：外层文件（`doc/ openspec/ tools/ *.ps1 *.md`）+ `ruoyi-vue-oa-master/`（后端）+ `ruoyi-vue-oa-ui-master/`（前端） | 打开仓库首页即可看到全部代码 |
| `backend` | `ruoyi-vue-oa-master` 的**完整历史**（其 master 分支） | 保留后端可增量 push 的历史 |
| `frontend` | `ruoyi-vue-oa-ui-master` 的**完整历史** | 保留前端历史 |
| `outer` | 外层仓库 `F:\dsh\ruoyiOA` 的**完整历史** | 保留外层文档/规格历史 |

> 三条历史分支是"完整性保险"：快照 master 本身不带三仓历史（orphan 提交）。

## 3. 执行步骤（前置全部满足后才做）

**前置门禁**：`openspec/changes/oa-purchase-sales-stock/tasks.md` 全 `[x]`、E2E `npx playwright test` 全绿、DEV-ENV §7 门禁逐项有结论、三仓 `git status` 只剩预期改动。

```powershell
# ① 三仓各自提交（提交信息按变更集分组；先确认没有误入库的敏感文件）
cd F:\dsh\ruoyiOA            ; git add -A ; git status --short   # 逐条确认，尤其 .auth/ 不得入库
                             ; git commit -m "docs+specs(2.0): B4 进销存交付与团队交付记录"
cd ruoyi-vue-oa-master       ; git add -A ; git status --short   ; git commit -m "feat(erp): 交付 B4 进销存（8 类单据/下推/过账红冲/调拨盘点/库存账/权限数据范围）"
cd ..\ruoyi-vue-oa-ui-master ; git add -A ; git status --short   ; git commit -m "feat(erp-ui): 交付 B4 进销存前端页面与单据壳"

# ② 加远端（SSH；ssh config 已处理 443/代理，直接用标准 SSH 形式）
cd F:\dsh\ruoyiOA
git remote add origin git@github.com:miyanagaaoi/ruoyiOA.git
git remote -v      # 应显示 fetch/push = git@github.com:miyanagaaoi/ruoyiOA.git

# ③ 三条历史分支：各自从自己的 master 推
#    外层
git push origin master:outer
#    后端
git -C ..\ruoyi-vue-oa-master push git@github.com:miyanagaaoi/ruoyiOA.git master:backend
#    前端
git -C ..\ruoyi-vue-oa-ui-master push git@github.com:miyanagaaoi/ruoyiOA.git master:frontend

# ④ monorepo 快照 → master（用 git archive，只取"已提交的 tracked 文件"，天然排除 .git/target/node_modules/日志）
$rel = "$env:TEMP\ruoyiOA-release"
if (Test-Path $rel) { Remove-Item -Recurse -Force $rel }
New-Item -ItemType Directory -Force -Path "$rel\ruoyi-vue-oa-master","$rel\ruoyi-vue-oa-ui-master" | Out-Null
cd F:\dsh\ruoyiOA
git archive HEAD | tar -x -C $rel
git -C ..\ruoyi-vue-oa-master   archive HEAD | tar -x -C "$rel\ruoyi-vue-oa-master"
git -C ..\ruoyi-vue-oa-ui-master archive HEAD | tar -x -C "$rel\ruoyi-vue-oa-ui-master"
cd $rel
git init -b master
git add -A
git commit -m "release: OA 2.0 B3+B4 交付快照（外层文档/规格/工具 + 后端 ruoyi-vue-oa-master + 前端 ruoyi-vue-oa-ui-master）"
git remote add origin git@github.com:miyanagaaoi/ruoyiOA.git
git push origin master

# ⑤ 复核
git ls-remote git@github.com:miyanagaaoi/ruoyiOA.git   # 应看到 master / backend / frontend / outer 四条
```

## 4. 安全与红线

- **绝不入库**：`ruoyi-vue-oa-ui-master/tests/e2e/.auth/`（含真实 token，已 gitignore）、`logs/ uploadPath/ .cache/ env/ .agent-teams/ .dsh/`。
  推送前对 `$rel` 与三仓各做一次 `git status --short` + 关键字扫描（`token|password|secret|id_ed25519|BEGIN OPENSSH`）。
- 私有 SSH 私钥**只在本机使用**，不得复制进任何仓库；`.ssh` 永不入库。
- 若用户后续要"只保留一个仓库"：三条历史分支可随时删除，不影响 `master` 快照。
- 推送后把四条 ref 与首/末提交哈希写进 `HANDOFF.md` 的收尾清单。
