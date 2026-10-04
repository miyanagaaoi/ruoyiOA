<template>
  <!--
    可复用的「手写签名画板」。

    抽出来的原因：手写采集现在有**两个**使用方 ——
      · 审批时的签名弹窗（`SignaturePad/index.vue`，手写 Tab）
      · 个人中心「我的签名」新增预存签名
    两边如果各写一份 canvas + 清空 + 空白校验，就会有两套"什么时候算空白"
    的判定，改一处漏一处。这里只做一件事：画一张图出来。

    ⚠ `vue-esign@1.1.4` **没有撤销能力**（只有 reset），所以这里提供"清空"。
    依据：PRD 8.3（PC 鼠标 / H5 触屏，输出透明底 PNG，空白签名被拒绝）。
  -->
  <div class="sign-canvas">
    <div class="sc-wrap" :style="{ minHeight: height + 'px' }">
      <vue-esign
        ref="esign"
        :width="width"
        :height="height"
        :is-crop="true"
        :line-width="4"
        line-color="#101c4a"
        bg-color=""
      />
    </div>
    <div class="sc-bar">
      <span class="sc-hint">{{ hint }}</span>
      <el-button size="mini" icon="el-icon-delete" @click="reset">清空</el-button>
    </div>
  </div>
</template>

<script>
import vueEsign from 'vue-esign'

/** 空画布时 `capture()` reject 的哨兵错误：调用方据此区分"用户还没写"与"真失败" */
export const EMPTY_SIGN_ERROR = 'EMPTY_SIGN'

export function isEmptySignError(err) {
  return !!(err && (err.message === EMPTY_SIGN_ERROR || err === EMPTY_SIGN_ERROR))
}

export default {
  name: 'SignatureCanvas',
  components: { vueEsign },
  props: {
    width: { type: Number, default: 820 },
    /** 移动端竖屏书写区要够高（PRD 8.3：≥240px） */
    height: { type: Number, default: 300 },
    /** 画板下方的提示文案（"正在上传…"这类进度提示由使用方传入） */
    hint: { type: String, default: '' }
  },
  methods: {
    reset() {
      if (this.$refs.esign) {
        this.$refs.esign.reset()
      }
    },

    /**
     * 取图：返回透明底 PNG 的 base64。
     *
     * `generate()` 在画布为空时会 **reject**，这里据此做"非空白校验"，
     * 不必自己去数像素 —— 库已经做了判断，重复实现只会多一处可能出错的地方。
     *
     * @returns {Promise<string>} 成功给 dataURL；空白画布 reject `EMPTY_SIGN_ERROR`
     */
    capture() {
      if (!this.$refs.esign) {
        return Promise.reject(new Error('签名画板未就绪'))
      }
      return this.$refs.esign.generate().then(dataURL => {
        if (!dataURL) {
          throw new Error(EMPTY_SIGN_ERROR)
        }
        return dataURL
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.sc-wrap {
  border: 1px dashed var(--oa-color-hairline-strong, #dcdfe6);
  border-radius: 4px;
  /* 移动端竖屏书写区要够高（PRD 8.3：≥240px） */
  min-height: 240px;
  overflow: hidden;
  background: #fff;
}
.sc-bar {
  display: flex;
  align-items: center;
  margin-top: 8px;
}
.sc-hint {
  margin-right: auto;
  color: var(--oa-color-ink-subtle, #909399);
  font-size: 12px;
}
</style>
