<template>
  <el-dialog
    :visible.sync="visible"
    custom-class="print-modal"
    width="920px"
    top="4vh"
    append-to-body
    :close-on-click-modal="false"
    @closed="onClosed"
  >
    <div slot="title" class="pm-head">
      <span class="pm-title">打印预览</span>
      <!--
        ⚠ 这里**不要**写死"纸张/方向/打印范围"：升级前写的是「A4 纵向 · 仅表单信息」，
        而 2.0 B2 之后纸张与方向取自生效打印模板（可能是 A3 横向）、出栏内容也由模板开关决定
        （签批栏/抄送栏/附件清单），写死就会与实际打印件**不一致**（踩过：浮层标题说"仅表单信息"，
        页内工具条却是"表单信息 + 签批栏"）。所以这里只说"按生效模板渲染"这类不会过期的话。
      -->
      <span class="pm-hint">按生效打印模板渲染（纸张/方向与出栏内容以页内工具条为准）</span>
    </div>

    <div class="pm-body">
      <!--
        为什么用 iframe 而不是把预览页直接渲染进浮层：

        1. **打印隔离**：点「打印」时调的是 iframe 自己的 window.print()，
           浏览器只会打印该 iframe 的文档 —— 天然与应用外壳（侧栏/顶栏/标签页）隔离，
           不需要任何 @media print 去壳技巧，也不受宿主页面结构变化影响。
        2. **不跳转页面**：当前列表/详情的滚动位置、筛选条件、分页全部保留。
        3. **复用**：打印页仍是一个独立路由（且是顶层路由、不套 Layout），
           浮层只是把它嵌进来，两者共用同一份实现。

        embedded=1 让预览页隐藏自己的「关闭」按钮 —— 关闭改由本浮层的 × 负责。
      -->
      <iframe
        v-if="src"
        ref="frame"
        :src="src"
        class="pm-frame"
        frameborder="0"
      />
    </div>
  </el-dialog>
</template>

<script>
/**
 * 打印预览浮层。
 *
 * 用**命令式**挂载（见 @/plugins/printPreview），不占宿主页面的模板：
 *   this.$openPrintPreview(businessId[, printTplId])
 *
 * 这样列表页 / 详情页都不需要引入组件、不需要维护 visible 状态。
 */
export default {
  name: 'PrintDialog',
  data() {
    return {
      visible: false,
      src: ''
    }
  },
  methods: {
    /**
     * 打开浮层。
     * @param {String} businessId 单据业务 ID（必填）
     * @param {String} [printTplId] 指定打印模板；不传则由服务端挑启用的模板（无则系统默认）
     */
    open(businessId, printTplId) {
      const query = { businessId: businessId, embedded: '1' }
      if (printTplId) {
        query.printTplId = printTplId
      }
      const { href } = this.$router.resolve({ path: '/workflow/print', query })
      // 先清空再下一帧赋值：强制 iframe 重建，
      // 否则连续打印不同单据时 iframe 会复用上一单的文档。
      this.src = ''
      this.visible = true
      this.$nextTick(() => {
        this.src = href
      })
    },
    onClosed() {
      // 关闭即卸掉 iframe，停止它的定时器与请求，避免留着后台轮询
      this.src = ''
    }
  }
}
</script>

<style lang="scss" scoped>
.pm-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.pm-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.pm-hint {
  font-size: 12px;
  color: #909399;
}
.pm-body {
  padding: 0;
}
.pm-frame {
  display: block;
  width: 100%;
  height: 72vh;
  border: 0;
  background: #fff;
}
</style>
