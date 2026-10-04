<template>
  <div class="flow-tree">
    <template v-for="(node, i) in nodes">
      <!-- ============ 节点卡片 ============ -->
      <div
        :key="'n' + node.id"
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
            <!-- 排序：仅选中时出现，避免画布变吵 -->
            <span v-if="d.isActive(p(i)) && !d.isSys(node)" class="ft-move">
              <button
                class="ft-mv"
                type="button"
                title="上移"
                :disabled="i === 0 || d.isSys(nodes[i - 1])"
                @click.stop="d.moveNode(p(i), -1)"
              >↑</button>
              <button
                class="ft-mv"
                type="button"
                title="下移"
                :disabled="i >= nodes.length - 1 || d.isSys(nodes[i + 1])"
                @click.stop="d.moveNode(p(i), 1)"
              >↓</button>
            </span>
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
        :key="'c' + node.id"
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
              <span class="ft-lane-name">{{ br.name || ('分支' + (j + 1)) }}</span>
              <span v-if="br.defaultBranch" class="ft-fallback">系统兜底</span>
              <span v-else class="ft-cond" :title="d.condText(br)">{{ d.condText(br) }}</span>
              <span v-if="!br.defaultBranch" class="ft-prio">优先级 {{ j + 1 }}</span>
              <el-button
                v-if="!br.defaultBranch"
                type="text"
                size="mini"
                class="ft-lane-op"
                @click.stop="d.editCondition(p(i, j))"
              >编辑条件</el-button>
              <el-button
                v-if="!br.defaultBranch"
                type="text"
                size="mini"
                class="ft-lane-op"
                @click.stop="d.removeBranch(p(i, j))"
              >删除</el-button>
              <el-button
                type="text"
                size="mini"
                class="ft-lane-op"
                @click.stop="d.toggleLane(p(i, j))"
              >{{ d.isCollapsed(p(i, j)) ? '展开' : '折叠' }}</el-button>
            </div>

            <!-- 泳道内容：递归 —— 折叠时收起 -->
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
      <div :key="'p' + node.id" class="ft-conn">
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
    </template>

    <!-- 空链：这一层没有任何节点 -->
    <div v-if="!nodes || !nodes.length" class="ft-empty">
      <span>此处还没有节点</span>
      <el-button type="text" size="mini" @click="d.insertEmpty(pathPrefix)">＋ 添加第一个节点</el-button>
    </div>
  </div>
</template>

<script>
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
 */
export default {
  name: 'FlowTree',
  props: {
    nodes: { type: Array, default: () => [] },
    /** 本层在整棵树中的路径前缀 */
    pathPrefix: { type: Array, default: () => [] }
  },
  inject: {
    flowDesigner: { default: null }
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
    }
  }
}
</script>

<style lang="scss" scoped>
.flow-tree {
  display: flex;
  flex-direction: column;
  align-items: center;
}

/* ---------- 节点卡片 ---------- */
.ft-node {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  min-width: 220px;
  max-width: 340px;
  min-height: 62px;
  border: 1px solid #d8d8d8;
  border-radius: 6px;
  background: #fff;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
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
  .ft-move {
    display: inline-flex;
    gap: 2px;
  }
  .ft-mv {
    width: 16px;
    height: 16px;
    line-height: 14px;
    padding: 0;
    border: none;
    border-radius: 3px;
    background: rgba(255, 255, 255, 0.18);
    color: #fff;
    font-size: 12px;
    cursor: pointer;
    transition: background 0.15s;

    &:hover:not(:disabled) { background: rgba(255, 255, 255, 0.38); }
    &:disabled { opacity: 0.3; cursor: not-allowed; }
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

  /* 第二行：节点名 + 摘要 */
  .ft-node-body {
    display: flex;
    align-items: baseline;
    gap: 8px;
    min-width: 0;
    padding: 8px 12px 10px;
  }
  .ft-name {
    font-weight: 500;
    color: #333;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ft-sum {
    margin-left: auto;
    color: #aaa;
    font-size: 11px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* 起止节点：灰蓝 */
  &.kind-sys {
    background: #f6f8fa;
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
  padding: 16px 14px;
}

/* 分段横线：靠 -14px 负外边距跨过泳道间距，与相邻泳道拼接 */
.laneline {
  position: absolute;
  height: 1px;
  background: #d5d5d5;
  left: -14px;
  right: -14px;
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
  height: 16px;
  background: #d5d5d5;
  transform: translateX(-50%);
}
.ft-lane::before { top: 0; }
.ft-lane::after { bottom: 0; }

/* 容器上下与父/子节点相连的竖线 */
.ft-stub {
  width: 1px;
  height: 16px;
  background: #d5d5d5;
  flex: none;
}

/* 泳道头 */
.ft-lane-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  max-width: 100%;
  border: 1px solid #dfe4ea;
  border-radius: 4px;
  background: #fff;
  padding: 4px 8px;
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

  .ft-lane-name {
    font-weight: 500;
    color: #5b7fbf;
  }
  .ft-fallback { color: #aaa; }
  .ft-cond {
    border: 1px solid #ddd;
    border-radius: 10px;
    padding: 0 6px;
    color: #666;
    max-width: 180px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .ft-prio {
    color: #4a9e6f;
    font-size: 11px;
  }
  .ft-lane-op {
    padding: 0 2px;
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
  border: 1px dashed #dcdcdc;
  border-radius: 6px;
  padding: 10px 18px;
  color: #aaa;
  font-size: 12px;
  background: #fcfcfc;
}
</style>
