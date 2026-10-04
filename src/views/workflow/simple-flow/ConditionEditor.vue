<template>
  <div class="cond-editor">
    <!-- 自然语言回显：防"且/或"理解错 -->
    <el-alert type="success" :closable="false" class="mb10">
      <div slot="title">
        <b>当前条件读作：</b>{{ sentence }}
      </div>
    </el-alert>

    <div v-for="(group, gi) in groups" :key="gi" class="cond-group">
      <div class="cond-group-head">
        <span>条件组 {{ gi + 1 }}</span>
        <span class="tip">组内为「且」</span>
        <el-button
          v-if="groups.length > 1"
          type="text"
          icon="el-icon-delete"
          class="fr"
          @click="removeGroup(gi)"
        >删除</el-button>
      </div>

      <div v-for="(row, ri) in group.rows" :key="ri" class="cond-row">
        <el-select
          v-model="row.field"
          placeholder="选择表单字段"
          size="small"
          class="fld"
          filterable
          allow-create
          default-first-option
        >
          <el-option
            v-for="f in fieldOptions"
            :key="f.vModel"
            :label="f.label + '（' + f.vModel + '）'"
            :value="f.vModel"
            :disabled="f.disabled"
          >
            <span>{{ f.label }}（{{ f.vModel }}）</span>
            <span class="opt-tail">{{ f.required ? '必填' : '未设必填' }}</span>
          </el-option>
        </el-select>
        <el-select v-model="row.op" placeholder="比较符" size="small" class="op">
          <el-option v-for="op in opOptions" :key="op.value" :label="op.label" :value="op.value" />
        </el-select>
        <el-input
          v-model="row.value"
          placeholder="值"
          size="small"
          class="val"
          :disabled="row.op === 'EMPTY' || row.op === 'NOT_EMPTY'"
        />
        <el-button type="text" icon="el-icon-close" @click="removeRow(gi, ri)" />
      </div>

      <el-button type="text" icon="el-icon-plus" @click="addRow(gi)">添加条件</el-button>
    </div>

    <el-button type="primary" plain size="mini" icon="el-icon-plus" @click="addGroup">添加条件组（组间为「或」）</el-button>

    <el-alert v-if="!fieldOptions.length" type="warning" :closable="false" class="mt10">
      <div slot="title">
        当前流程<b>尚未关联表单</b>：请先在设计器顶部选择「关联表单」，字段清单会自动从表单提取；
        未关联时只能手工输入字段名（<b>发布校验会跳过字段级检查</b>）。
      </div>
    </el-alert>
    <el-alert v-else type="warning" :closable="false" class="mt10">
      <div slot="title">
        字段已从表单自动提取，<b>只能选择已设为「必填」的字段</b>（未设必填的置灰）；
        比较值按<b>选项中文名</b>比较（提交时写入的是中文标签）。
        整句会被包进一对 <code>${ }</code>（写成 <code>${A} == 'B'</code> 运行期会报 non-Boolean）。
      </div>
    </el-alert>
  </div>
</template>

<script>
/**
 * 图形化条件组编辑器（对应 PRD 6.3.1 / 原型 S18）
 *
 * 逻辑：条件组内为「且」，条件组之间为「或」，形如 (A 且 B) 或 (C 且 D)。
 * 只提供"选字段 / 选比较符 / 填值"三件套——不提供表达式框与脚本入口。
 *
 * **内嵌形态**：不再自带 el-dialog。它直接挂在配置抽屉的「进入条件」里，
 * 与抽屉内其它字段一样即时编辑（branch.groups 直接改，引用即生效）。
 * 空行在提交前由 designer 的 pruneConditions() 清理，不在这里拦截。
 */
export default {
  name: 'ConditionEditor',
  props: {
    /** 分支（泳道）对象；组件直接读写它的 groups */
    branch: { type: Object, default: null },
    /** 从表单提取出来的字段清单（{vModel,label,required,disabled}） */
    fieldOptions: { type: Array, default: () => [] }
  },
  data() {
    return {
      opOptions: [
        { value: 'EQ', label: '等于' },
        { value: 'NE', label: '不等于' },
        { value: 'GT', label: '大于' },
        { value: 'GE', label: '大于等于' },
        { value: 'LT', label: '小于' },
        { value: 'LE', label: '小于等于' },
        { value: 'CONTAINS', label: '包含' },
        { value: 'NOT_CONTAINS', label: '不包含' },
        { value: 'EMPTY', label: '为空' },
        { value: 'NOT_EMPTY', label: '不为空' }
      ]
    }
  },
  computed: {
    groups() {
      return (this.branch && this.branch.groups) || []
    },
    sentence() {
      const parts = []
      ;(this.groups || []).forEach(g => {
        const rows = (g.rows || [])
          .filter(r => r.field)
          .map(r => {
            const opLabel = (this.opOptions.find(o => o.value === r.op) || {}).label || r.op
            if (r.op === 'EMPTY' || r.op === 'NOT_EMPTY') {
              return `${r.field} ${opLabel}`
            }
            return `${r.field} ${opLabel} ${r.value === undefined || r.value === '' ? '？' : r.value}`
          })
        if (rows.length) {
          parts.push(rows.length > 1 ? `(${rows.join(' 且 ')})` : rows[0])
        }
      })
      if (!parts.length) {
        return '尚未设置条件'
      }
      return '当 ' + parts.join(' 或 ') + ' 时，进入本分支'
    }
  },
  watch: {
    branch: {
      immediate: true,
      handler() {
        this.ensureGroups()
      }
    }
  },
  methods: {
    /** 保证至少有一个条件组（编辑态始终可见一行可填） */
    ensureGroups() {
      const b = this.branch
      if (!b) return
      if (!b.groups || !b.groups.length) {
        this.$set(b, 'groups', [{ logic: 'AND', rows: [{ field: '', op: 'EQ', value: '' }] }])
      }
    },
    addGroup() {
      this.groups.push({ logic: 'AND', rows: [{ field: '', op: 'EQ', value: '' }] })
    },
    removeGroup(gi) {
      this.groups.splice(gi, 1)
      this.ensureGroups()
    },
    addRow(gi) {
      this.groups[gi].rows.push({ field: '', op: 'EQ', value: '' })
    },
    removeRow(gi, ri) {
      this.groups[gi].rows.splice(ri, 1)
      if (!this.groups[gi].rows.length) {
        this.addRow(gi)
      }
    }
  }
}
</script>

<style scoped>
.cond-editor {
  min-width: 0;
}
.cond-group {
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  padding: 8px 10px;
  margin-bottom: 8px;
  background: #fcfcfc;
}
.cond-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
  color: #555;
}
.cond-group-head .tip {
  color: #999;
  font-size: 12px;
}
.cond-group-head .fr {
  margin-left: auto;
}
/* 抽屉内宽度有限：字段/值自适应，比较符定宽，整体可换行 */
.cond-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.cond-row .fld {
  flex: 1 1 150px;
  min-width: 0;
}
.cond-row .op {
  flex: 0 0 92px;
}
.cond-row .val {
  flex: 1 1 90px;
  min-width: 0;
}
.opt-tail {
  float: right;
  color: #aaa;
  font-size: 12px;
  margin-left: 12px;
}
.mb10 {
  margin-bottom: 10px;
}
.mt10 {
  margin-top: 10px;
}
</style>
