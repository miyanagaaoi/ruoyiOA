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
        <span class="ft-badge">{{ d.nodeBadge(node) }}</span>
        <span class="ft-name">{{ node.name || d.typeLabel(node.type) }}</span>
        <span class="ft-sum">{{ d.summary(node) }}</span>
      </div>

      <!-- ============ 容器：分支并列泳道 ============ -->
      <div
        v-if="d.isContainer(node)"
        :key="'c' + node.id"
        class="ft-lanes"
        :class="{ 'single-lane': (node.branches || []).length <= 1 }"
      >
        <div class="ft-split" :style="laneVars(node)" />
        <div class="ft-lane-row">
          <div
            v-for="(br, j) in (node.branches || [])"
            :key="br.id"
            class="ft-lane"
          >
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
          </div>
        </div>
        <div class="ft-merge" :style="laneVars(node)" />

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
    },
    /** 泳道数注入 CSS 变量，供分叉/汇合横杠计算位置 */
    laneVars(node) {
      const n = Math.max(1, (node.branches || []).length)
      return { '--lanes': n }
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
  align-items: center;
  gap: 8px;
  min-width: 190px;
  max-width: 320px;
  border: 1px solid #d8d8d8;
  border-top: 3px solid #d8d8d8;
  border-radius: 6px;
  background: #fff;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 13px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition: border-color 0.15s, box-shadow 0.15s;

  &:hover {
    border-color: #b5b5b5;
    box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
  }
  &.active {
    border-color: #e8820c;
    box-shadow: 0 0 0 3px rgba(232, 130, 12, 0.12);
  }

  .ft-badge {
    font-size: 11px;
    color: #fff;
    background: #b9b9b9;
    border-radius: 3px;
    padding: 1px 6px;
    flex: none;
  }
  .ft-name {
    font-weight: 500;
    color: #333;
  }
  .ft-sum {
    margin-left: auto;
    color: #aaa;
    font-size: 11px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* 起止节点：灰蓝、无强调 */
  &.kind-sys {
    background: #f4f6f8;
    border-top-color: #9fb0c0;
    .ft-badge { background: #9fb0c0; }
  }
  /* 审批：橙色（只给"要人做决定"的节点） */
  &.kind-approve {
    border-top-color: #e8820c;
    .ft-badge { background: #e8820c; }
  }
  /* 办理 */
  &.kind-handle {
    border-top-color: #4a9e6f;
    .ft-badge { background: #4a9e6f; }
  }
  /* 抄送：浅色虚线，无决策权 */
  &.kind-cc {
    border-style: dashed;
    border-top-style: dashed;
    background: #fcfcfc;
    color: #777;
  }
  /* 容器本身（条件/并行）作为卡片出现时 */
  &.kind-container {
    border-top-color: #5b7fbf;
    background: #f7f9fc;
    .ft-badge { background: #5b7fbf; }
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
.ft-lanes {
  width: 100%;
}
.ft-lane-row {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: center;
  gap: 16px;
}
.ft-lane {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

/* 分叉 / 汇合横杠：位置随泳道数变化（修正写死 2 条的缺陷） */
.ft-split,
.ft-merge {
  position: relative;
  height: 18px;
  margin: 0 auto;
  width: 100%;

  &::before {
    content: '';
    position: absolute;
    left: calc(50% / var(--lanes, 2));
    right: calc(50% / var(--lanes, 2));
    top: 50%;
    height: 1px;
    background: #d5d5d5;
  }
}

/* 单泳道：不画横杠（视觉退化为一条竖线） */
.single-lane .ft-split::before,
.single-lane .ft-merge::before {
  display: none;
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
