# OpenDesign 技能（引入版）

> **来源**：[nexu-io/open-design](https://github.com/nexu-io/open-design)（Apache-2.0）· 中文说明见 [`docs/i18n/README.zh-CN.md`](https://github.com/nexu-io/open-design/blob/main/docs/i18n/README.zh-CN.md)
> **引入日期**：2026-10-04　**引入方式**：摘取可移植部分本地化（不是装 CLI/桌面端）
> **许可证**：`craft/` 内容改编自 MIT 许可的 [refero_skill](https://github.com/referodesign/refero_skill)（© Refero Design）；技能与模板目录为 Apache-2.0。本目录保留原许可与出处标注。

---

## 1. 为什么是"引入技能"而不是"装工具"

OpenDesign 完整形态是一套工作区（守护进程 + 桌面端 + `od` CLI + MCP）。但它有四条正交的轴，其中两条是**纯文本、Agent 原生、可移植**的：

| 轴 | 形态 | 能否直接引入本项目 |
| --- | --- | --- |
| `craft/` | 品牌无关的**通用 UI 工艺规则**（排版、颜色、状态覆盖、无障碍、反 AI 味…） | ✅ 纯 Markdown，直接可用 |
| `design-templates/` | 渲染蓝图（`SKILL.md` + `assets/`） | ✅ 纯 Markdown + 资源 |
| `skills/` | 功能技能（`SKILL.md`） | ✅ 同上 |
| `design-systems/` | 品牌包（`DESIGN.md` + `tokens.css`） | ⚠️ 需按本项目主题改写，不能照搬 |
| `od` CLI / MCP / 桌面端 | 工作区运行时 | ❌ 需守护进程；且本会话工具集固定，无法中途接 MCP |

**关键**：`craft/` 与 `SKILL.md` 用的是 **Claude Code Agent Skills 约定**，与 DSH 自身技能格式同构，所以"引入"在这里等价于**把规则放到项目里、让 Agent 按它自检**，零依赖、可回退。

---

## 2. 已收录

| 文件 | 作用 | 何时该用 |
| --- | --- | --- |
| [`craft/anti-ai-slop.md`](craft/anti-ai-slop.md) | 七条"AI 味"硬规则（默认 indigo 强调色、两段式渐变、emoji 当图标、编造指标、填充文案…）+ P1/P2 软性提示 | **任何**要出视觉产物的任务 |
| [`craft/state-coverage.md`](craft/state-coverage.md) | 交互界面**必须渲染的五态**（加载/空/错误/有数据/极端值）+ 表单三态 + ARIA 与焦点规则 + 重试纪律 | 任何列表/表格/表单/面板 |
| [`design-templates/dashboard.SKILL.md`](design-templates/dashboard.SKILL.md) | 后台/分析看板的渲染蓝图（左导航 + 顶栏 + KPI 行 + 图表行 + 表格行） | 做后台页面时 |

## 3. 未收录（按需拉取）

上游文件会演进，这里不复制全部。需要时按下面的 URL 直接取：

```
https://raw.githubusercontent.com/nexu-io/open-design/main/craft/<slug>.md
https://raw.githubusercontent.com/nexu-io/open-design/main/design-templates/<template>/SKILL.md
```

**craft 可用 slug**（节选自上游 `craft/README.md`）：

| slug | 适用场景 |
| --- | --- |
| `typography` | 任何有文字的产物（≈全部） |
| `typography-hierarchy` | 层级需要"被设计过"而非拼装的界面 |
| `color` | 任何有配色的产物 |
| `animation-discipline` | 有动效的界面（转场、微交互） |
| `accessibility-baseline` | 任何可交互 UI（焦点/标签/键盘路径） |
| `form-validation` | 以表单为主体的界面 |
| `laws-of-ux` | 涉及认知极限的编排（价格页、看板、引导流、弹窗） |
| `rtl-and-bidi` | 可能渲染阿拉伯语/希伯来语/波斯语 |

---

## 4. 在本项目怎么用

本项目是 **Vue2 + Element UI**，所以引入时要**做一次适配**，不能把上游的 Web 原型规则照抄：

| 上游规则 | 本项目适配 |
| --- | --- |
| 强调色用 `var(--accent)`，禁用 Tailwind indigo `#6366f1` 等 | 用本项目主题色（Element UI 主色），不引入 Tailwind |
| 图表用手写内联 SVG，不引图表库 | 看板类可沿用；业务页用现成组件 |
| 单文件 HTML 产物 + `<artifact>` 输出契约 | **不适用**：本项目交付的是 `.vue` 单文件组件 |
| `data-od-id` 标记区域（供评论模式） | 可改为语义化 class/`data-*`，便于联调定位 |
| `state-coverage` 五态 | **完全适用且必须**：列表/表格/表单都要有 加载·空·错误·有数据·极端值 |
| `anti-ai-slop` 七宗罪 | **完全适用**：尤其"emoji 当图标""编造指标""填充文案""圆角卡+左侧色边" |

### 我方约定（写 UI 时按此自检）

1. **五态齐全**：新页面/新组件提交前，逐个自问 加载 / 空 / 错误 / 有数据 / 极端值 是否都有明确呈现。
2. **错误必须回答三问**：发生了什么 → 为什么 → 用户能做什么；表单失败**不清空已填内容**。
3. **校验时机**：失焦校验，不在首次按键就报错；输入合法立刻撤掉错误提示。
4. **不编造数据**：占位用明确标注的占位（如"示例数据"），不写 "10× faster""99.9%"。
5. **不用 emoji 当图标**：用现成图标库。
6. **强调色克制**：一屏内可见强调色 ≤2 处。
7. **空态是独立状态**：有标题 + 解释 + 主行动，不做成一片空白或只写"No data"。

---

## 5. 与 OpenDesign 官方工作区的区别

本目录只引入**规则与蓝图**。若要使用上游的完整能力（151 个品牌设计系统、277 个插件、HyperFrames HTML→MP4、`od` CLI/MCP 接入编码 Agent），需要另外安装：

| 方式 | 命令/入口 | 说明 |
| --- | --- | --- |
| 桌面应用（零配置） | [open-design.ai](https://open-design.ai/) / GitHub Releases | 自动检测 PATH 上的编码 Agent |
| Docker | `cd deploy && docker compose up -d` → `http://localhost:7456` | 需要 `OD_API_TOKEN` |
| 接入编码 Agent | `od mcp install <agent>` | 支持 DeepSeek Harness（`od agent setup deepseek-harness`） |

> 上游明确提示：daemon 存储路径以仓库根 `AGENTS.md` 的 **Daemon data directory contract** 为唯一权威，文档里不复述。
