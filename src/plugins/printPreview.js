/**
 * 打印预览浮层（全局）
 * ----------------------------------------------------------------------------
 * 用法：任意组件里 `this.$openPrintPreview(businessId[, printTplId])`
 *
 * 为什么做成**命令式单例**而不是在每个页面里塞一个 <print-dialog>：
 *   打印入口有三处（待办列表 / 我的草稿 / 单据详情），若各自维护 visible 状态与
 *   组件引用，等于把同一段样板抄三遍，且新增入口时容易漏。这里只在首次调用时
 *   挂载一次，之后复用同一个实例。
 *
 * 浮层 `append-to-body`，挂在 document.body 下，所以不会被 #app 的样式或
 * 打印媒体规则波及。
 */
import Vue from 'vue'
import router from '@/router'
import PrintDialog from '@/views/workflow/print/PrintDialog.vue'

const PrintDialogCtor = Vue.extend(PrintDialog)
let instance = null

/**
 * 打开打印预览浮层（不跳转页面，不开新窗口）。
 * @param {String} businessId 单据业务 ID
 * @param {String} [printTplId] 可选：指定打印模板
 * @returns {Object} 浮层实例（一般不需要）
 */
export function openPrintPreview(businessId, printTplId) {
  if (!businessId) {
    return null
  }
  if (!instance) {
    // 显式传 router：命令式挂载的组件不在 router-view 里，拿不到注入的 $router
    instance = new PrintDialogCtor({ router })
    instance.$mount()
    document.body.appendChild(instance.$el)
  }
  instance.open(businessId, printTplId)
  return instance
}

Vue.prototype.$openPrintPreview = openPrintPreview

export default { openPrintPreview }
