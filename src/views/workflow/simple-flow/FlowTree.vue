<template>
  <div class="flow-tree">
    <!--
      ========== 同链拖拽排序（替代原先的 ↑ / ↓ 按钮） ==========
      · vuedraggable 直接绑定本层的**响应式数组**（顶层 flow.nodes / 泳道内 br.nodes），
        排序走真正的 array.splice，所以「保存草稿」序列化出来的就是新顺序。
      · 每条链（顶层主链 + 每条泳道内的链）各拿一个带本组件实例 uid 的 group 名：
        Sortable 只在 group.name 相同的列表之间搬运，名字互不相同
        ⇒ 天然只允许「同链内」重排，跨链 / 跨泳道拖不进去。
      · 起止节点（sys）是锚点：filter 先挡一道；就算漏网，onDragEnd 里也会按对象身份
        把 sys 节点从数组摘掉、再按拖动前记下的下标插回去（见 restoreSysAnchors）。
      · 拖完必须让 designer 按**节点对象身份**重算 selection.path（reorderStart / reorderEnd），
        否则选中的还是旧下标，会静默打开另一个节点的配置面板。
    -->
    <draggable
      :list="nodes"
      :group="chainGroup"
      class="ft-chain"
      draggable=".ft-item"
      filter=".kind-sys, .ft-del, .ft-lanes, .ft-conn"
      :prevent-on-filter="false"
      :animation="150"
      ghost-class="ft-ghost"
      chosen-class="ft-chosen"
      drag-class="ft-drag"
      @start="onDragStart"
      @end="onDragEnd"
    >
      <!-- 一个 ft-item = 一张卡片（连同它下面的泳道与连接点），是排序的最小单位 -->
      <div
        v-for="(node, i) in nodes"
        :key="node.id"
        class="ft-item"
      >
        <!-- ============ 节点卡片 ============ -->
        <div
          class="ft-node"
          :class="[d.kindClass(node), { active: d.isActive(p(i)) }]"
          role="button"
          tabindex="0"
          @click="d.select(p(i))"
          @keyup.enter="d.select(p(i))"
        >
          <div class="ft-node-head">
            <span class="ft-badge">{{ d.nodeBadge(node) }}</span>
            <!-- 起止节点不显示类型标签（名称本身就是"发起"/"结束"，避免重复） -->
            <span
              v-if="node.type !== 'start' && node.type !== 'end'"
              class="ft-type"
            >{{ d.typeLabel(node.type) }}</span>
            <span class="ft-head-right">
              <button
                v-if="!d.isSys(node)"
                class="ft-del"
                type="button"
                :title="'删除节点：' + (node.name || d.typeLabel(node.type))"
                @click.stop="d.removeNode(p(i))"
              >×</button>
            </span>
          </div>
          <div class="ft-node-body">
            <span class="ft-name">{{ node.name || d.typeLabel(node.type) }}</span>
            <span class="ft-sum">{{ d.summary(node) }}</span>
          </div>
        </div>

        <!-- ============ 容器：分支并列泳道 ============ -->
        <div
          v-if="d.isContainer(node)"
          class="ft-lanes"
        >
          <div class="ft-stub" />
          <div class="ft-lane-row">
            <div
              v-for="(br, j) in (node.branches || [])"
              :key="br.id"
              class="ft-lane"
            >
              <span class="laneline laneline-top" />
              <!-- 泳道头 -->
              <div
                class="ft-lane-head"
                :class="{ active: d.isActive(p(i, j)), fallback: br.defaultBranch }"
                role="button"
                tabindex="0"
                @click.stop="d.select(p(i, j))"
                @keyup.enter="d.select(p(i, j))"
              >
                <div class="ft-lh-top">
                  <span class="ft-lane-name">{{ br.name || ('分支' + (j + 1)) }}</span>
                  <span v-if="!br.defaultBranch" class="ft-prio">优先级 {{ j + 1 }}</span>
                  <span v-else class="ft-fallback">系统兜底</span>
                  <button
                    v-if="!br.defaultBranch"
                    class="ft-lane-del"
                    type="button"
                    title="删除该分支"
                    @click.stop="d.removeBranch(p(i, j))"
                  >×</button>
                </div>
                <div class="ft-lh-main">
                  <span v-if="!br.defaultBranch" class="ft-cond">{{ d.condText(br) }}</span>
                  <span v-else class="ft-cond fallback-desc">如存在未满足其他分支条件的情况，则进入此分支</span>
                </div>
              </div>

              <!-- 泳道内容：递归 —— 折叠时收起（递归出来的也是一条可拖拽的同链） -->
              <flow-tree
                v-show="!d.isCollapsed(p(i, j))"
                :nodes="br.nodes || []"
                :path-prefix="p(i, j)"
              />
              <span class="laneline laneline-bottom" />
            </div>
          </div>
          <div class="ft-stub" />

          <!-- 容器级：添加分支 -->
          <div class="ft-add-lane">
            <el-button
              v-if="node.type === 'condition'"
              type="text"
              size="mini"
              icon="el-icon-plus"
              @click.stop="d.addBranch(p(i))"
            >添加条件分支</el-button>
            <span v-else class="ft-note">并行分支由表单多选字段动态生成，无需在此增删</span>
          </div>
        </div>

        <!-- ============ 连接点：唯一插入入口 ============ -->
        <div class="ft-conn">
          <el-dropdown trigger="click" placement="right" @command="cmd => d.insertAfter(p(i), cmd)">
            <button class="ft-plus" type="button" :title="'在「' + (node.name || '') + '」之后插入节点'">+</button>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item command="approve">审批节点</el-dropdown-item>
              <el-dropdown-item command="handle">办理节点</el-dropdown-item>
              <el-dropdown-item command="condition">条件分支</el-dropdown-item>
              <el-dropdown-item command="parallel">并行分支（会审）</el-dropdown-item>
              <el-dropdown-item command="cc">抄送节点</el-dropdown-item>
              <el-dropdown-item command="end" divided>结束节点</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </div>
    </draggable>

    <!-- 空链：这一层没有任何节点（放在 draggable 之外，Sortable 不会把它当成一个可拖项） -->
    <div v-if="!nodes || !nodes.length" class="ft-empty">
      <span>此处还没有节点</span>
      <el-button type="text" size="mini" @click="d.insertEmpty(pathPrefix)">＋ 添加第一个节点</el-button>
    </div>
  </div>
</template>

<script>
import draggable from 'vuedraggable'

/**
 * 流程画布（递归组件）—— 飞书式「纵向主链 + 横向并列泳道」
 *
 * 对应文档：doc/飞书流程设计页面.md §10 §11
 *
 * 设计要点：
 *  1. 布局**不手算坐标**：链用 column flex、泳道用 row flex，只有分叉/汇合横杠要按泳道数算
 *     （left/right = calc(50% / var(--lanes))）——这修正了 wireframes.html 里写死 25% 只能画 2 条分支的缺陷。
 *  2. 组件自引用实现任意层嵌套；`v-for` 的 key 一律用节点 id（不能用 index，否则结构编辑会复用错组件）。
 *  3. 所有结构性操作通过 inject 的 `flowDesigner` 回调上抛，不在递归层里层层 $emit。
 *  4. 节点/泳道统一用**路径**寻址：path 为 [节点下标, 泳道下标, 节点下标, 泳道下标, …] 交替。
 *  5. 排序用 vuedraggable（同链内），排序结果直接落在响应式数组上 —— 见模板顶部注释。
 */
export default {
  name: 'FlowTree',
  components: { draggable },
  props: {
    nodes: { type: Array, default: () => [] },
    /** 本层在整棵树中的路径前缀 */
    pathPrefix: { type: Array, default: () => [] }
  },
  inject: {
    flowDesigner: { default: null }
  },
  data() {
    return {
      /**
       * 同链拖拽的 Sortable 分组，名字带本组件实例的 uid。
       * Sortable 只在 group.name 相同的列表之间搬运 ⇒ 名字互不相同就永远拖不进别的链。
       * 放在 data（而不是内联对象字面量）里，是为了让对象引用稳定，
       * 免得 $attrs 的深度侦听每次都把 group 重设一遍。
       */
      chainGroup: { name: 'ft-chain-' + this._uid },
      /** 拖动开始前记下的起止锚点：{ node, index } */
      sysAnchors: null
    }
  },
  computed: {
    d() {
      return this.flowDesigner || {}
    }
  },
  methods: {
    /** 由本层下标构造子路径 */
    p() {
      const args = Array.prototype.slice.call(arguments)
      return this.pathPrefix.concat(args)
    },
    /** 是否为起止节点（宿主没注入回调时按"不是"处理，保持画布可渲染） */
    isSys(node) {
      return !!(this.d.isSys && this.d.isSys(node))
    },
    /**
     * 拖动开始：记下本链所有 sys 节点的下标，并请宿主按对象身份锚定当前选中项。
     * vuedraggable 的 start 事件在 nextTick 里派发，此刻数组还没变，下标是拖动前的。
     */
    onDragStart() {
      const nodes = this.nodes || []
      const anchors = []
      nodes.forEach((n, i) => {
        if (this.isSys(n)) anchors.push({ node: n, index: i })
      })
      this.sysAnchors = anchors
      if (this.d.reorderStart) this.d.reorderStart()
    },
    /**
     * 拖动结束：先复位 sys 锚点，再让宿主重算 selection.path。
     * 两者顺序不能颠倒 —— 锚点复位自己会改下标，重算必须看到最终数组。
     */
    onDragEnd() {
      this.restoreSysAnchors()
      this.sysAnchors = null
      if (this.d.reorderEnd) this.d.reorderEnd()
    },
    /**
     * 起止节点锚点复位。
     * 不依赖 Sortable 的 filter 是否拦住：即使 sys 节点自己被动、或被普通节点越过去，
     * 也按**对象身份**把它从数组里摘出来，再按拖动前记下的下标插回去。
     * 下标越界时钳到末尾，保证「发起在最前、结束在最后」的相对次序不丢。
     */
    restoreSysAnchors() {
      const anchors = this.sysAnchors
      if (!anchors || !anchors.length) return
      const nodes = this.nodes || []
      anchors.forEach(a => {
        const i = nodes.indexOf(a.node)
        if (i >= 0) nodes.splice(i, 1)
      })
      anchors.forEach(a => {
        nodes.splice(Math.min(a.index, nodes.length), 0, a.node)
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.flow-tree {
  /*
   * 画布横向/纵向尺寸的唯一定义处。
   * 卡片、泳道头、空态共用 --node-w，保证同级卡片持久对齐；
   * --lane-gap 同时决定泳道左右留白与分段横线的负外边距 ——
   * 两者必须相等，否则相邻泳道的横线接不上（原先硬编码 14px 写了两处）。
   */
  --node-w: 280px;
  --lane-gap: 14px;
  --stub-h: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

/*
 * 链 = 一个 draggable 容器；.ft-item = 一张卡片（含它的泳道与连接点）。
 * 两层都 align-self: stretch —— 保持原先「.flow-tree 的直接子元素撑满宽度」的算法，
 * 这样泳道里的 .ft-lanes { width: 100% } 与之前完全一致。
 */
.ft-chain {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.ft-item {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  align-items: center;
}

/* ---------- 节点卡片 ---------- */
.ft-node {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  /* 宽度锁死为同一个 --node-w。
     box-sizing 必须与泳道头/空态一致，否则这里多出 2px 边框宽，左右各偏 1px；
     min/max-width 一起固定，防止 flex item 的 min-width:auto 被长文本撑宽。 */
  width: var(--node-w);
  min-width: var(--node-w);
  max-width: var(--node-w);
  box-sizing: border-box;
  min-height: 62px;
  border: 1px solid #d8d8d8;
  border-radius: 6px;
  background: #fff;
  padding: 0;
  overflow: hidden;
  /* 排序改成拖拽后，卡片本身就是拖拽手柄 —— 用可拖动光标做第一层可发现性提示 */
  cursor: grab;
  font-size: 13px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition: border-color 0.15s, box-shadow 0.15s;

  &:hover {
    border-color: #b5b5b5;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
    .ft-del { opacity: 1; }
  }
  &.active {
    border-color: #e8820c;
    box-shadow: 0 0 0 3px rgba(232, 130, 12, 0.12);
    .ft-del { opacity: 1; }
  }

  /* 第一行：实色标题条（用底色编码节点类型，对齐飞书卡片） */
  .ft-node-head {
    display: flex;
    align-items: center;
    gap: 6px;
    min-height: 22px;
    padding: 3px 8px 3px 10px;
    background: #b9b9b9;
    color: #fff;
  }
  .ft-badge {
    font-size: 11px;
    line-height: 15px;
    color: #fff;
    background: rgba(255, 255, 255, 0.28);
    border-radius: 3px;
    padding: 0 5px;
    flex: none;
  }
  .ft-type {
    font-size: 11px;
    letter-spacing: 0.5px;
    color: #fff;
    opacity: 0.95;
  }
  /* 标题条右侧操作区 */
  .ft-head-right {
    margin-left: auto;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    flex: none;
  }
  /* 删除按钮：低透明度常驻，悬停/选中时全亮 */
  .ft-del {
    flex: none;
    width: 16px;
    height: 16px;
    line-height: 14px;
    text-align: center;
    padding: 0;
    border: none;
    border-radius: 3px;
    background: transparent;
    color: #fff;
    font-size: 14px;
    cursor: pointer;
    opacity: 0.5;
    transition: opacity 0.15s, background 0.15s;

    &:hover {
      opacity: 1;
      background: rgba(255, 255, 255, 0.28);
    }
  }

  /* 第二行：节点名 + 摘要（宽度不够时摘要换行，不截断内容） */
  .ft-node-body {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: 2px 8px;
    min-width: 0;
    padding: 8px 12px 10px;
  }
  .ft-name {
    min-width: 0;
    font-weight: 500;
    color: #333;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ft-sum {
    min-width: 0;
    color: #aaa;
    font-size: 11px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* 起止节点：灰蓝；锚点不可拖动，光标回到普通指针 */
  &.kind-sys {
    background: #f6f8fa;
    cursor: pointer;
    .ft-node-head { background: #8a9bab; }
  }
  /* 审批：橙色（只给"要人做决定"的节点） */
  &.kind-approve .ft-node-head { background: #e8820c; }
  /* 办理 */
  &.kind-handle .ft-node-head { background: #4a9e6f; }
  /* 抄送：虚线边框 + 浅灰条，无决策权 */
  &.kind-cc {
    border-style: dashed;
    .ft-node-head { background: #b9b9b9; }
  }
  /* 容器（条件/并行） */
  &.kind-container {
    background: #f7f9fc;
    .ft-node-head { background: #5b7fbf; }
  }
}

/* ---------- 拖动反馈（Sortable 挂的类） ----------
 * native DnD 下 ghostClass / chosenClass 都挂在被拖的那一项上：
 * ghost = 留在列表里的落点占位（虚线 + 浅橙底，沿用 .ft-empty 的"虚位"语言），
 * chosen = 被抓起的那一项（只补 z-index 与抓取光标，避免与 ghost 打架）。
 */
.ft-item {
  &.ft-ghost {
    .ft-node {
      border-style: dashed;
      border-color: #e8820c;
      background: #fffaf3;
      box-shadow: none;
    }
  }
  &.ft-chosen {
    position: relative;
    z-index: 3;
    cursor: grabbing;
    .ft-node { cursor: grabbing; }
  }
  &.ft-drag {
    opacity: 0.9;
  }
}

/* ---------- 连接点 ---------- */
.ft-conn {
  display: flex;
  justify-content: center;
  padding: 2px 0;
}
.ft-plus {
  width: 24px;
  height: 24px;
  line-height: 20px;
  border-radius: 50%;
  border: 1px solid #cfcfcf;
  background: #fff;
  color: #999;
  font-size: 15px;
  cursor: pointer;
  padding: 0;
  transition: all 0.15s;

  &:hover {
    border-color: #e8820c;
    color: #e8820c;
    transform: scale(1.08);
  }
}

/* ---------- 容器 / 泳道 ---------- */
/*
 * 泳道按**内容宽度**自适应（不再等宽拉伸）。
 * 分叉/汇合线改为「每条泳道各画一段」拼接 —— 这样与泳道宽度无关，
 * 1 / 2 / N 条、宽窄不一都能正确对齐；原先那套 calc(50% / var(--lanes))
 * 只能处理等宽泳道。
 */
.ft-lanes {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.ft-lane-row {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  justify-content: center;
}
.ft-lane {
  position: relative;
  flex: 0 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--stub-h) var(--lane-gap);
}

/* 分段横线：靠 -var(--lane-gap) 负外边距跨过泳道间距，与相邻泳道拼接 */
.laneline {
  position: absolute;
  height: 1px;
  background: #d5d5d5;
  left: calc(-1 * var(--lane-gap));
  right: calc(-1 * var(--lane-gap));
}
.laneline-top { top: 0; }
.laneline-bottom { bottom: 0; }

/* 首段只画右半（自泳道中心起），末段只画左半 */
.ft-lane:first-child .laneline { left: 50%; }
.ft-lane:last-child .laneline { right: 50%; }
/* 单泳道退化：不画横线，只留一条竖线 */
.ft-lane:only-child .laneline { display: none; }

/* 横线到泳道头（尾）的竖直短线 */
.ft-lane::before,
.ft-lane::after {
  content: '';
  position: absolute;
  left: 50%;
  width: 1px;
  height: var(--stub-h);
  background: #d5d5d5;
  transform: translateX(-50%);
}
.ft-lane::before { top: 0; }
.ft-lane::after { bottom: 0; }

/* 容器上下与父/子节点相连的竖线 */
.ft-stub {
  width: 1px;
  height: var(--stub-h);
  background: #d5d5d5;
  flex: none;
}

/* 泳道头 */
.ft-lane-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  /* 与同级卡片**完全同宽**（同一个 --node-w），保证左右边缘持久对齐 */
  width: var(--node-w);
  min-width: var(--node-w);
  max-width: var(--node-w);
  box-sizing: border-box;
  /*
   * ★ 固定高度 —— 这是「同级卡片持久对齐」的关键。
   * 泳道头内容多少不一（有条件的 2 行、兜底的 1 行），若按内容自适应，
   * 下方卡片就会从不同的 y 开始，看起来左右两列错位。
   * 条件文本用 -webkit-line-clamp 截断到 2 行，保证内容不会把高度撑破。
   */
  height: 80px;
  overflow: hidden;
  border: 1px solid #dfe4ea;
  border-radius: 4px;
  background: #fff;
  padding: 6px 8px;
  margin-bottom: 6px;
  cursor: pointer;
  font-size: 12px;

  &:hover { border-color: #b5b5b5; }
  &.active {
    border-color: #e8820c;
    box-shadow: 0 0 0 2px rgba(232, 130, 12, 0.12);
  }
  &.fallback {
    background: #fafafa;
    color: #999;
  }

  .ft-lh-top {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: none;
    min-height: 18px;
  }
  /* 右上角删除（与节点卡片的 × 保持同一套视觉） */
  .ft-lane-del {
    margin-left: auto;
    flex: none;
    width: 16px;
    height: 16px;
    line-height: 14px;
    text-align: center;
    padding: 0;
    border: none;
    border-radius: 3px;
    background: transparent;
    color: #c0c4cc;
    font-size: 14px;
    cursor: pointer;
    transition: color 0.15s, background 0.15s;

    &:hover {
      color: #f56c6c;
      background: #fef0f0;
    }
  }
  .ft-lh-main {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
  }
  .ft-lane-name {
    font-weight: 500;
    color: #5b7fbf;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ft-fallback { color: #aaa; }

  /* 条件文本：最多两行，超出截断 —— 恒定高度是等高的前提 */
  .ft-cond {
    flex: 1 1 auto;
    min-width: 0;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    border: 1px solid #ddd;
    border-radius: 4px;
    padding: 2px 8px;
    color: #666;
    line-height: 1.5;
    word-break: break-word;
    overflow-wrap: anywhere;

    &.fallback-desc {
      border-color: #eee;
      color: #aaa;
    }
  }
  .ft-prio {
    flex: none;
    color: #4a9e6f;
    font-size: 11px;
  }
}

.ft-add-lane {
  text-align: center;
  padding: 4px 0 0;
}
.ft-note {
  color: #b0b0b0;
  font-size: 11px;
}

/* ---------- 空链 ---------- */
.ft-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: var(--node-w);
  min-width: var(--node-w);
  max-width: var(--node-w);
  box-sizing: border-box;
  border: 1px dashed #dcdcdc;
  border-radius: 6px;
  padding: 10px 18px;
  color: #aaa;
  font-size: 12px;
  background: #fcfcfc;
}
</style>
