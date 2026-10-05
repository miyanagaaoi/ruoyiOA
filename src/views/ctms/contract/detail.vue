<template>
  <el-dialog
    :title="title"
    :visible.sync="visible"
    width="1160px"
    top="5vh"
    append-to-body
    :close-on-click-modal="false"
    @closed="handleClosed"
  >
    <div v-loading="loading" class="contract-detail">
      <!--
        合同详情 / 编辑（2.0 B3 任务 8.2）—— 既是新增表单，也是详情页（只读态）。

        形态说明（为什么是弹窗而不是路由页）：本仓库业务页面一律走 `sys_menu` 动态路由，
        而菜单 SQL 里 `ctms/contract/index` 是合同台账唯一的菜单（详情没有独立菜单行），
        所以「详情/编辑页」以**弹窗组件**挂在列表页上（同目录 `detail.vue`，由 `index.vue` 打开）。
        不要在 `router/index.js` 里补业务路由 —— 见 `src/views/ctms/README.md` 的约定。

        三条口径写在这里：
        1. 金额：行总价 = 数量 × 单价（**先**按 HALF_UP 舍入到 2 位），合同金额 = 各行总价之和，
           与后端 `ContractRules` 逐条对齐，实现见同目录 `./contract-rules.js`（十进制定点，不用浮点）。
        2. 质保到期日**只读且不参与提交**：新增与编辑都由服务端按「生效日 + 期限」重算，
           这里只是把同一个算法显示出来（前端传值不参与判定）。
        3. 附件限制（20MB / 后缀白名单）**从 `/ctms/attachment/object-types` 取**，
           前端不写第二份常量；上传/删除服务端要求 `ctms:contract:edit` **且** `ctms:attachment:list`。
      -->
      <DataLoadError v-if="loadError" :text="loadError" :cause="loadErrorCause" @retry="handleRetry" />
      <el-alert
        v-if="form.delFlag === '1'"
        type="warning"
        :closable="false"
        show-icon
        class="mb8"
        title="该合同已停用（软删除）：本页只读。请先在列表页「恢复」后再编辑。"
      />
      <el-alert
        v-if="optionsError"
        type="warning"
        :closable="false"
        show-icon
        class="mb8"
        :title="optionsError"
      />

      <!-- ==================== 基础信息 ==================== -->
      <div class="section-title">基础信息</div>
      <el-form ref="form" :model="form" :rules="rules" label-width="104px" :disabled="readonly">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="合同编号" prop="contractNo">
              <el-input v-model="form.contractNo" maxlength="17" clearable placeholder="留空＝服务端生成" />
            </el-form-item>
            <div class="field-tip">
              <el-button
                type="text"
                size="mini"
                :loading="nextNoLoading"
                @click="handlePreviewNo"
                v-hasPermi="['ctms:contract:add']"
                >取号预览</el-button
              >
              <span v-if="nextNoPreview" class="tip-text">预览编号：{{ nextNoPreview }}（不占号；留空保存即由服务端生成）</span>
              <span v-if="nextNoError" class="text-danger">{{ nextNoError }}</span>
            </div>
          </el-col>
          <el-col :span="8">
            <el-form-item label="合同名称" prop="name">
              <el-input v-model="form.name" maxlength="200" placeholder="必填" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="合同类型" prop="type">
              <el-select v-model="form.type" placeholder="请选择" style="width: 100%">
                <el-option
                  v-for="dict in dict.type.contract_types"
                  :key="dict.value"
                  :label="dict.label"
                  :value="dict.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="我方主体" prop="subjectCode">
              <el-select v-model="form.subjectCode" placeholder="请选择" clearable style="width: 100%">
                <el-option v-for="dict in dict.type.subjects" :key="dict.value" :label="dict.label" :value="dict.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="客户档案" prop="customerId">
              <el-select v-model="form.customerId" filterable clearable placeholder="销售方向（可只填文本）" style="width: 100%">
                <el-option v-for="item in customerOptions" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="供应商档案" prop="supplierId">
              <el-select v-model="form.supplierId" filterable clearable placeholder="采购方向（可只填文本）" style="width: 100%">
                <el-option v-for="item in supplierOptions" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="甲方" prop="partyA">
              <el-input v-model="form.partyA" maxlength="128" placeholder="留空则由客户档案名称带出" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="乙方" prop="partyB">
              <el-input v-model="form.partyB" maxlength="128" placeholder="留空则由供应商档案名称带出" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="签订日期" prop="signDate">
              <el-date-picker
                v-model="form.signDate"
                type="date"
                value-format="yyyy-MM-dd"
                placeholder="yyyy-MM-dd（编号月份码取它）"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="生效日期" prop="effectiveDate">
              <el-date-picker
                v-model="form.effectiveDate"
                type="date"
                value-format="yyyy-MM-dd"
                placeholder="yyyy-MM-dd"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="币种" prop="currency">
              <el-input v-model="form.currency" maxlength="8" placeholder="默认 CNY（只记录不换算）" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="累计已付" prop="paidAmount">
              <el-input-number v-model="form.paidAmount" :precision="2" :min="0" :controls="false" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="进度状态" prop="status">
              <el-select v-model="form.status" placeholder="留空＝字典默认值" clearable style="width: 100%">
                <el-option
                  v-for="dict in dict.type.contract_statuses"
                  :key="dict.value"
                  :label="dict.label"
                  :value="dict.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="到货状态" prop="arrivalStatus">
              <el-select v-model="form.arrivalStatus" placeholder="留空＝字典默认值" clearable style="width: 100%">
                <el-option
                  v-for="dict in dict.type.arrival_statuses"
                  :key="dict.value"
                  :label="dict.label"
                  :value="dict.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="预计到货日期" prop="expectedArrivalDate">
              <el-date-picker
                v-model="form.expectedArrivalDate"
                type="date"
                value-format="yyyy-MM-dd"
                placeholder="yyyy-MM-dd"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="经办人" prop="ownerName">
              <el-input v-model="form.ownerName" maxlength="64" placeholder="自由文本" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="框架合同" prop="isFramework">
              <el-switch v-model="form.isFramework" active-value="1" inactive-value="0" @change="handleFrameworkChange" />
              <span class="tip-text">打开后本合同的子合同由「父框架合同」栏挂接</span>
            </el-form-item>
          </el-col>
          <el-col v-if="form.isFramework !== '1'" :span="8">
            <el-form-item label="父框架合同" prop="parentId">
              <el-select
                v-model="form.parentId"
                filterable
                clearable
                placeholder="仅框架合同可作父级"
                style="width: 100%"
                @change="handleParentChange"
              >
                <el-option
                  v-for="item in frameworkOptions"
                  :key="item.id"
                  :label="item.contractNo + ' ' + item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注" prop="remark">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
            </el-form-item>
          </el-col>
        </el-row>

        <!-- ==================== 行项明细 ==================== -->
        <div class="section-title">行项明细</div>
        <el-form-item label="行项" prop="items">
          <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAddItem">添加行项</el-button>
          <span class="tip-text">行总价 = 数量 × 单价（先按 2 位四舍五入）；每一行都必须选择物料档案</span>
        </el-form-item>
        <el-table :data="form.items" size="mini" border class="item-table">
          <el-table-column label="序号" align="center" width="55">
            <template slot-scope="scope">{{ scope.$index + 1 }}</template>
          </el-table-column>
          <el-table-column label="行项类型" align="center" width="110">
            <template slot-scope="scope">
              <el-select v-model="scope.row.itemType" size="mini" placeholder="请选择" style="width: 100%">
                <el-option
                  v-for="dict in dict.type.item_types"
                  :key="dict.value"
                  :label="dict.label"
                  :value="dict.value"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="物料档案" align="left" width="220">
            <template slot-scope="scope">
              <el-select
                v-model="scope.row.productId"
                size="mini"
                filterable
                placeholder="按编码/名称过滤"
                style="width: 100%"
                @change="handleProductChange(scope.row)"
              >
                <el-option
                  v-for="item in productOptions"
                  :key="item.id"
                  :label="productLabel(item)"
                  :value="item.id"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="行项名称" align="left" width="150">
            <template slot-scope="scope">
              <el-input v-model="scope.row.name" size="mini" maxlength="128" placeholder="留空回落物料名称" />
            </template>
          </el-table-column>
          <el-table-column label="规格" align="left" width="130">
            <template slot-scope="scope">
              <el-input v-model="scope.row.spec" size="mini" maxlength="128" placeholder="留空回落物料规格" />
            </template>
          </el-table-column>
          <el-table-column label="数量" align="center" width="110">
            <template slot-scope="scope">
              <el-input-number
                v-model="scope.row.qty"
                size="mini"
                :precision="3"
                :min="0"
                :controls="false"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="单价" align="center" width="120">
            <template slot-scope="scope">
              <el-input-number
                v-model="scope.row.unitPrice"
                size="mini"
                :precision="4"
                :min="0"
                :controls="false"
                style="width: 100%"
              />
            </template>
          </el-table-column>
          <el-table-column label="总价" align="right" width="110">
            <template slot-scope="scope">
              <span>{{ lineTotalText(scope.row.qty, scope.row.unitPrice) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" align="left" min-width="120">
            <template slot-scope="scope">
              <el-input v-model="scope.row.remark" size="mini" maxlength="200" />
            </template>
          </el-table-column>
          <el-table-column label="操作" align="center" width="70">
            <template slot-scope="scope">
              <el-button type="text" size="mini" icon="el-icon-delete" @click="handleRemoveItem(scope.$index)"
                >删除</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <div class="amount-line">
          <span class="amount-label">合同金额（行项汇总）</span>
          <span class="amount-value">{{ amountSumText }}</span>
          <span class="tip-text">每行先依次舍入到 2 位再求和（后端 C-1 口径）；保存时由服务端按行项重算并覆盖</span>
        </div>

        <!-- ==================== 质保区 ==================== -->
        <div class="section-title">质保</div>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="启用质保" prop="hasWarranty">
              <el-switch v-model="form.hasWarranty" active-value="1" inactive-value="0" />
            </el-form-item>
          </el-col>
          <template v-if="form.hasWarranty === '1'">
            <el-col :span="8">
              <el-form-item label="质保生效日" prop="warrantyStart">
                <el-date-picker
                  v-model="form.warrantyStart"
                  type="date"
                  value-format="yyyy-MM-dd"
                  placeholder="yyyy-MM-dd"
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="质保期限" prop="warrantyMonths">
                <el-input-number v-model="form.warrantyMonths" :min="1" :precision="0" :step="1" style="width: 100%" />
                <span class="tip-text">单位：月（至少 1）</span>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="质保到期日">
                <el-input :value="warrantyEndText" readonly placeholder="由生效日与期限自动推算" />
                <span class="tip-text">只读：新增与编辑都由服务端重算，本页传值不参与</span>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="质保金额" prop="warrantyAmount">
                <el-input-number
                  v-model="form.warrantyAmount"
                  :precision="2"
                  :min="0"
                  :controls="false"
                  style="width: 100%"
                  @change="handleWarrantyAmountChange"
                />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="质保比例(%)" prop="warrantyRate">
                <el-input-number
                  v-model="form.warrantyRate"
                  :precision="4"
                  :min="0"
                  :controls="false"
                  style="width: 100%"
                  @change="handleWarrantyRateChange"
                />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="质保状态">
                <el-tag v-if="form.warrantyReleased === '1'" type="info" size="mini">已释放</el-tag>
                <el-tag v-else type="success" size="mini">未释放</el-tag>
                <el-button
                  v-if="form.warrantyReleased !== '1' && mode === 'edit' && form.id"
                  type="text"
                  size="mini"
                  icon="el-icon-circle-check"
                  :loading="releaseLoading"
                  @click="handleReleaseWarranty"
                  v-hasPermi="['ctms:contract:edit']"
                  >释放质保</el-button
                >
                <span v-if="form.warrantyReleaseDate" class="tip-text">释放日期：{{ form.warrantyReleaseDate }}</span>
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="质保备注" prop="warrantyNote">
                <el-input v-model="form.warrantyNote" type="textarea" :rows="2" maxlength="500" show-word-limit />
              </el-form-item>
            </el-col>
          </template>
        </el-row>

        <!-- ==================== 标签 ==================== -->
        <div class="section-title">标签</div>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="手动标签" prop="manualTagIds">
              <el-select v-model="form.manualTagIds" multiple filterable clearable placeholder="可多选；这里只维护手动标签" style="width: 100%">
                <el-option v-for="tag in tagOptions" :key="tag.id" :label="tag.name" :value="tag.id" />
              </el-select>
              <span v-if="tagOptionError" class="text-danger">{{ tagOptionError }}</span>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="自动标签">
              <template v-if="form.autoTags && form.autoTags.length">
                <el-tag v-for="tag in form.autoTags" :key="tag.id" size="mini" type="success" class="mr4">{{ tag.name }}</el-tag>
              </template>
              <span v-else class="text-muted">—</span>
              <div class="tip-text">自动标签（类型名、框架合同）由服务端维护，不能在这里增删</div>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <!-- ==================== 附件区 ==================== -->
      <div class="section-title">附件</div>
      <div v-if="mode === 'add' || !form.id" class="tip-text">
        附件要挂在已保存的合同上（主键由服务端生成）：请先「保存」本页，再上传附件。
      </div>
      <div v-else>
        <div class="attachment-bar">
          <el-upload
            ref="upload"
            action="#"
            :show-file-list="false"
            :before-upload="beforeUpload"
            :http-request="handleUploadRequest"
            :disabled="!canWriteAttachment || readonly"
            class="attachment-upload"
          >
            <el-button
              v-if="canWriteAttachment"
              size="mini"
              type="primary"
              plain
              icon="el-icon-upload2"
              :disabled="readonly"
              :loading="uploadLoading"
              v-hasPermi="['ctms:contract:edit']"
              >上传附件</el-button
            >
          </el-upload>
          <el-button size="mini" type="text" icon="el-icon-refresh" @click="loadAttachments" v-hasPermi="['ctms:attachment:list']"
            >刷新</el-button
          >
          <span class="tip-text">{{ attachmentLimitText }}</span>
        </div>
        <span v-if="attachmentError" class="text-danger">{{ attachmentError }}</span>
        <el-table :data="attachments" size="mini" border v-loading="attachmentLoading">
          <el-table-column label="文件名" prop="fileName" min-width="240" show-overflow-tooltip />
          <el-table-column label="大小" align="right" width="100">
            <template slot-scope="scope">{{ humanSize(scope.row.sizeBytes) }}</template>
          </el-table-column>
          <el-table-column label="上传人" align="center" width="110">
            <template slot-scope="scope">{{ scope.row.createBy || '—' }}</template>
          </el-table-column>
          <el-table-column label="上传时间" align="center" width="160">
            <template slot-scope="scope">{{ parseTime(scope.row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" align="center" width="150">
            <template slot-scope="scope">
              <el-button
                type="text"
                size="mini"
                icon="el-icon-download"
                :loading="attachmentDownloadingId === scope.row.id"
                @click="handleDownloadAttachment(scope.row)"
                v-hasPermi="['ctms:attachment:list']"
                >下载</el-button
              >
              <el-button
                v-if="canWriteAttachment"
                type="text"
                size="mini"
                icon="el-icon-delete"
                :loading="attachmentDeletingId === scope.row.id"
                @click="handleDeleteAttachment(scope.row)"
                v-hasPermi="['ctms:contract:edit']"
                >删除</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- ==================== 变更历史 ==================== -->
      <template v-if="form.id">
        <div class="section-title">变更历史</div>
        <span v-if="changeLogError" class="text-danger">{{ changeLogError }}</span>
        <el-table :data="changeLogs" size="mini" border v-loading="changeLogLoading">
          <el-table-column label="时间" align="center" width="160">
            <template slot-scope="scope">{{ parseTime(scope.row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作人" align="center" width="110">
            <template slot-scope="scope">{{ scope.row.operatorName || '—' }}</template>
          </el-table-column>
          <el-table-column label="字段" align="center" width="150">
            <template slot-scope="scope">{{ fieldLabel(scope.row.fieldName) }}</template>
          </el-table-column>
          <el-table-column label="旧值" align="left" min-width="150" show-overflow-tooltip>
            <template slot-scope="scope">{{ scope.row.oldValue || '—' }}</template>
          </el-table-column>
          <el-table-column label="新值" align="left" min-width="150" show-overflow-tooltip>
            <template slot-scope="scope">{{ scope.row.newValue || '—' }}</template>
          </el-table-column>
          <el-table-column label="来源" align="center" width="90">
            <template slot-scope="scope">
              <el-tag :type="scope.row.source === 'auto' ? 'warning' : 'info'" size="mini">{{
                scope.row.source === 'auto' ? '自动' : '手动'
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="备注" align="left" min-width="140" show-overflow-tooltip>
            <template slot-scope="scope">{{ scope.row.note || '—' }}</template>
          </el-table-column>
        </el-table>
        <div class="tip-text">共 {{ changeLogTotal }} 条，展示最近 {{ changeLogs.length }} 条（按时间倒序）</div>
      </template>

      <!-- ==================== 只读「关联单据」 ==================== -->
      <div class="section-title">关联单据（只读）</div>
      <el-table
        :data="form.relatedDocs || []"
        size="mini"
        border
        empty-text="B3 阶段暂无关联单据（采购/销售/出入库单据属 B4）；该区块只读，不会回写合同任何字段"
      >
        <el-table-column label="单据单号" prop="docNo" min-width="200" />
        <el-table-column label="单据类型" prop="docType" min-width="160" />
        <el-table-column label="单据状态" prop="status" min-width="140" />
      </el-table>
    </div>

    <div slot="footer" class="dialog-footer">
      <el-button v-if="!readonly" type="primary" :loading="submitLoading" @click="submitForm">保 存</el-button>
      <el-button @click="visible = false">关 闭</el-button>
    </div>
  </el-dialog>
</template>

<script>
import {
  getContract,
  addContract,
  updateContract,
  listContract,
  listTagOptions,
  listContractChangeLogs,
  nextContractNo,
  releaseWarranty
} from '@/api/ctms/contract'
import {
  listAttachment,
  getAttachmentObjectTypes,
  uploadAttachment,
  downloadAttachment,
  delAttachment
} from '@/api/ctms/attachment'
import { listCustomerOptions, listSupplierOptions } from '@/api/ctms/partner'
import { listProduct } from '@/api/ctms/masterdata'
import DataLoadError from '@/components/DataLoadError'
import { describeError } from '@/utils/errorMessage'
import { blobValidate } from '@/utils/ruoyi'
import { saveAs } from 'file-saver'
// 与后端 ContractRules 对齐的纯函数（金额/数量/质保到期）；命名带上 contract 前缀，
// 避免和 el-form 的 `:rules` 计算属性在阅读时混淆
import * as contractRules from './contract-rules'

/** 附件挂载的对象类型：合同（与后端 CtmsAttachmentObjectTypes.CONTRACT 一致） */
const OBJECT_TYPE_CONTRACT = 'contract'

/** 变更历史一次取回的条数（面板只展示最近若干条） */
const CHANGE_LOG_PAGE_SIZE = 50

/** 物料下拉与父框架下拉的取数上限（后端 startPage 缺参时只给 10 条，所以必须显式传） */
const PRODUCT_PAGE_SIZE = 500
const FRAMEWORK_PAGE_SIZE = 200

/**
 * 变更历史里"不是普通列名"的字段名 → 中文。
 * 普通列名后端本来就是中文（`合同编号`/`合同金额`/`质量保证金到期日`…），这里只补特殊名。
 */
const CHANGE_LOG_FIELD_LABELS = {
  _summary: '标的物摘要',
  _items: '行项明细',
  _tags: '标签',
  _origin: '数据来源',
  deleted: '停用状态',
  status: '进度状态（含终止原因）',
  '附件': '附件'
}

/** Date → yyyy-MM-dd（本地时区） */
function stampDay(date) {
  return (
    String(date.getFullYear()) +
    '-' +
    String(date.getMonth() + 1).padStart(2, '0') +
    '-' +
    String(date.getDate()).padStart(2, '0')
  )
}

/**
 * 后端日期 → yyyy-MM-dd 文本（日期控件只认它）。
 *
 * ⚠ 本仓库没有配置 `spring.jackson.date-format`（只有时区定制的 ApplicationConfig），
 *   所以 `java.util.Date` 的 JSON 形态**不固定**：可能是 ISO 串
 *   `2026-03-01T00:00:00.000+08:00`、也可能是 `yyyy-MM-dd HH:mm:ss`、还可能是毫秒数。
 *   直接把原值塞进 `value-format="yyyy-MM-dd"` 的控件会显示空白，所以统一在这里归一。
 */
function dayOnly(value) {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? stampDay(new Date(value)) : ''
  }
  const text = String(value).trim()
  if (/^\d{4}-\d{2}-\d{2}/.test(text)) {
    // `2026-03-01` / `2026-03-01 00:00:00` / `2026-03-01T00:00:00.000+08:00` 都取前 10 位
    return text.slice(0, 10)
  }
  if (/^\d+$/.test(text)) {
    return stampDay(new Date(Number(text)))
  }
  const parsed = new Date(text.replace(/-/g, '/').replace('T', ' ').replace(/\.\d{3}/g, ''))
  return isNaN(parsed.getTime()) ? '' : stampDay(parsed)
}

/** 字节数 → 人读文本 */
function humanSize(bytes) {
  const value = Number(bytes)
  if (!value || !isFinite(value) || value <= 0) {
    return '—'
  }
  if (value < 1024) {
    return value + ' B'
  }
  if (value < 1024 * 1024) {
    return (value / 1024).toFixed(1) + ' KB'
  }
  return (value / 1024 / 1024).toFixed(2) + ' MB'
}

/**
 * 空表单初值（新增态）。
 *
 * 做成**模块级函数**而不是"在 data() 里调 this.emptyForm()"：后者依赖
 * Vue 的初始化顺序（initMethods 早于 initData）才成立，读代码的人看不出这层依赖。
 */
function emptyFormState() {
  return {
    id: undefined,
    contractNo: undefined,
    name: undefined,
    type: undefined,
    subjectCode: undefined,
    customerId: undefined,
    supplierId: undefined,
    partyA: undefined,
    partyB: undefined,
    signDate: '',
    effectiveDate: '',
    currency: 'CNY',
    paidAmount: undefined,
    status: undefined,
    arrivalStatus: undefined,
    expectedArrivalDate: '',
    ownerName: undefined,
    isFramework: '0',
    parentId: undefined,
    remark: undefined,
    items: [],
    manualTagIds: [],
    autoTags: [],
    relatedDocs: [],
    delFlag: '0',
    hasWarranty: '0',
    warrantyStart: '',
    warrantyMonths: undefined,
    warrantyAmount: undefined,
    warrantyRate: undefined,
    warrantyNote: undefined,
    warrantyReleased: '0',
    warrantyReleaseDate: ''
  }
}

export default {
  name: 'CtmsContractDetail',
  components: { DataLoadError },
  dicts: ['contract_types', 'contract_statuses', 'arrival_statuses', 'subjects', 'item_types'],
  data() {
    return {
      visible: false,
      // add-新增 / edit-编辑 / view-详情只读
      mode: 'add',
      loading: false,
      loadError: '',
      loadErrorCause: '',
      submitLoading: false,
      releaseLoading: false,
      // 下拉选项（各自独立降级，不互相拖死）
      optionsError: '',
      tagOptions: [],
      tagOptionError: '',
      productOptions: [],
      customerOptions: [],
      supplierOptions: [],
      frameworkOptions: [],
      // 编号预览（读 msg）
      nextNoLoading: false,
      nextNoPreview: '',
      nextNoError: '',
      // 附件
      attachments: [],
      attachmentLoading: false,
      attachmentError: '',
      attachmentLimits: {},
      attachmentDownloadingId: '',
      attachmentDeletingId: '',
      uploadLoading: false,
      // 变更历史
      changeLogs: [],
      changeLogTotal: 0,
      changeLogLoading: false,
      changeLogError: '',
      // 当前打开的合同ID（打开瞬间就已知，用于把"自己"从父框架候选里剔掉 —— 详情是异步回来的）
      currentId: '',
      form: emptyFormState()
    }
  },
  computed: {
    title() {
      if (this.mode === 'add') {
        return '新增合同'
      }
      return this.mode === 'view' ? '合同详情' : '编辑合同'
    },
    /** 只读：详情态，或合同已停用（服务端同样拒绝编辑：「合同已停用，请先恢复」） */
    readonly() {
      return this.mode === 'view' || this.form.delFlag === '1'
    },
    /** 附件读权限 */
    canReadAttachment() {
      return this.$auth.hasPermi('ctms:attachment:list')
    },
    /**
     * 附件写权限：服务端要求 `ctms:contract:edit` **且** `ctms:attachment:list`。
     * ⚠ `v-hasPermi` 是"数组里任一命中即放行"（OR），表达不了 AND，所以用 $auth.hasPermiAnd 算布尔量。
     */
    canWriteAttachment() {
      return this.$auth.hasPermiAnd(['ctms:contract:edit', 'ctms:attachment:list']) && !this.readonly
    },
    canQueryPartner() {
      return this.$auth.hasPermi('ctms:partner:query')
    },
    canListProduct() {
      return this.$auth.hasPermi('ctms:partner:list')
    },
    canReadTags() {
      return this.$auth.hasPermi('ctms:tag:list')
    },
    /** 合同金额（行项汇总）：与后端 C-1 同口径的定点计算 */
    amountSumText() {
      const rows = this.form.items || []
      return contractRules.sumLineTotalsText(rows.map((row) => contractRules.lineTotalText(row.qty, row.unitPrice)))
    },
    /** 质保到期日（只读展示；提交时不带该字段，服务端会重算） */
    warrantyEndText() {
      if (this.form.hasWarranty !== '1') {
        return ''
      }
      return contractRules.warrantyEndText(this.form.warrantyStart, this.form.warrantyMonths)
    },
    attachmentLimitText() {
      const limits = this.attachmentLimits || {}
      const extensions = limits.extensions || []
      if (!limits.maxSizeBytes || !extensions.length) {
        return '附件限制口径尚未取到（/ctms/attachment/object-types）'
      }
      return (
        '单文件 ≤ ' +
        limits.maxSizeMb +
        'MB（' +
        limits.maxSizeBytes +
        ' 字节，等于上限也放行）；允许后缀：' +
        extensions.join(' / ') +
        '；口径来自 /ctms/attachment/object-types，不在前端写死'
      )
    },
    rules() {
      const form = this.form
      const itemRules = [
        {
          validator: (rule, value, callback) => {
            const rows = this.effectiveItems()
            for (let i = 0; i < rows.length; i++) {
              if (!rows[i].productId) {
                callback(new Error('行项第 ' + (i + 1) + ' 行必须选择物料档案'))
                return
              }
            }
            callback()
          },
          trigger: 'blur'
        }
      ]
      const base = {
        name: [{ required: true, message: '合同名称不能为空', trigger: 'blur' }],
        type: [{ required: true, message: '合同类型不能为空', trigger: 'change' }],
        items: itemRules,
        contractNo: [
          {
            validator: (rule, value, callback) => {
              const text = value === null || value === undefined ? '' : String(value).trim()
              if (text && text.length !== 17) {
                callback(new Error('手工/历史编号必须是 17 位（留空则由服务端生成）'))
                return
              }
              callback()
            },
            trigger: 'blur'
          }
        ]
      }
      if (form.hasWarranty === '1') {
        base.warrantyStart = [{ required: true, message: '质保生效日不能为空', trigger: 'change' }]
        base.warrantyMonths = [
          {
            validator: (rule, value, callback) => {
              if (value === null || value === undefined || Number(value) < 1) {
                callback(new Error('质保月数必须大于 0'))
                return
              }
              callback()
            },
            trigger: 'change'
          }
        ]
        base.warrantyRate = [
          {
            validator: (rule, value, callback) => {
              if (value === null || value === undefined || value === '') {
                callback()
                return
              }
              if (this.contractAmountValue() <= 0) {
                callback(new Error('合同金额为空或 0，无法换算质保金额：请先补行项或直接填质保金额'))
                return
              }
              callback()
            },
            trigger: 'change'
          }
        ]
      }
      return base
    }
  },
  methods: {
    /** 空表单（新增态的初值）：币种跟后端默认 CNY；其余留空由服务端兜底 */
    emptyForm() {
      return emptyFormState()
    },
    /* ==================== 打开与关闭 ==================== */
    openAdd() {
      this.form = this.emptyForm()
      this.mode = 'add'
      this.currentId = ''
      this.resetTransient()
      this.visible = true
      this.loadOptions()
    },
    openEdit(id) {
      this.openById(id, 'edit')
    },
    openView(id) {
      this.openById(id, 'view')
    },
    openById(id, mode) {
      this.form = this.emptyForm()
      this.mode = mode
      // 打开瞬间就记下目标 id：详情是异步回来的，而父框架候选要用它把"自己"剔掉
      this.currentId = id
      this.resetTransient()
      this.visible = true
      this.loadOptions()
      this.loadDetail(id)
    },
    resetTransient() {
      this.loadError = ''
      this.loadErrorCause = ''
      this.nextNoPreview = ''
      this.nextNoError = ''
      this.attachments = []
      this.attachmentError = ''
      this.changeLogs = []
      this.changeLogTotal = 0
      this.changeLogError = ''
      this.optionsError = ''
      this.tagOptionError = ''
      this.attachmentDownloadingId = ''
      this.attachmentDeletingId = ''
    },
    handleClosed() {
      this.form = this.emptyForm()
      this.mode = 'add'
      this.resetTransient()
    },
    /** 重试：按当前 mode 重新取数（详情态取详情，新增态只重取选项） */
    handleRetry() {
      if (this.form.id) {
        this.loadDetail(this.form.id)
        return
      }
      this.loadOptions()
    },
    /* ==================== 取数 ==================== */
    loadOptions() {
      // 四种选项各自独立降级（每个 fetch* 自带 catch，一个失败不拖死其它），
      // 这里只兜"降级本身抛错"的极端情况
      Promise.all([this.fetchTags(), this.fetchProducts(), this.fetchPartners(), this.fetchFrameworks()]).catch((err) => {
        const d = describeError(err)
        this.optionsError = '下拉选项加载失败：' + d.text
      })
    },
    fetchTags() {
      if (!this.canReadTags) {
        this.tagOptions = []
        this.tagOptionError = '当前账号无标签读取权限（ctms:tag:list），手动标签不可选'
        return Promise.resolve()
      }
      this.tagOptionError = ''
      return listTagOptions()
        .then((response) => {
          this.tagOptions = response.rows || []
        })
        .catch((err) => {
          this.tagOptions = []
          const d = describeError(err)
          this.tagOptionError = '标签选项加载失败：' + d.text
        })
    },
    fetchProducts() {
      if (!this.canListProduct) {
        this.productOptions = []
        return Promise.resolve()
      }
      return listProduct({ enableFlag: '1', pageNum: 1, pageSize: PRODUCT_PAGE_SIZE })
        .then((response) => {
          this.productOptions = this.mergeProductOptions(response.rows || [])
        })
        .catch((err) => {
          this.productOptions = this.mergeProductOptions([])
          const d = describeError(err)
          this.optionsError = '物料选项加载失败：' + d.text
        })
    },
    /**
     * 物料选项 = 启用物料清单 ∪ **本表单已引用的物料**。
     *
     * 为什么要并：行项引用过的物料可能已停用（不在 `enableFlag='1'` 的清单里），
     * 也可能排在前 500 条之外；只渲染接口给的那批，下拉会显示成裸 UUID。
     * 合并用的是行项里的**物料快照**（productCode/productName），不额外发请求。
     */
    mergeProductOptions(rows) {
      const options = (rows || []).slice()
      const items = this.form.items || []
      items.forEach((row) => {
        if (row.productId && !options.some((option) => option.id === row.productId)) {
          options.push({
            id: row.productId,
            code: row.productCode,
            name: row.productName || row.name,
            spec: row.spec
          })
        }
      })
      return options
    },
    fetchPartners() {
      if (!this.canQueryPartner) {
        this.customerOptions = []
        this.supplierOptions = []
        return Promise.resolve()
      }
      return Promise.all([
        listCustomerOptions()
          .then((response) => {
            this.customerOptions = response.data || []
          })
          .catch((err) => {
            this.customerOptions = []
            const d = describeError(err)
            this.optionsError = '客户选项加载失败：' + d.text
          }),
        listSupplierOptions()
          .then((response) => {
            this.supplierOptions = response.data || []
          })
          .catch((err) => {
            this.supplierOptions = []
            const d = describeError(err)
            this.optionsError = '供应商选项加载失败：' + d.text
          })
      ])
    },
    fetchFrameworks() {
      return listContract({ isFramework: '1', includeDeleted: '0', pageNum: 1, pageSize: FRAMEWORK_PAGE_SIZE })
        .then((response) => {
          const rows = response.rows || []
          // 自己不能挂到自己下面：用打开时就记下的 currentId（详情异步回来，form.id 可能还没值）
          this.frameworkOptions = rows.filter((row) => row.id !== this.currentId && row.id !== this.form.id)
        })
        .catch((err) => {
          this.frameworkOptions = []
          const d = describeError(err)
          this.optionsError = '框架合同选项加载失败：' + d.text
        })
    },
    loadDetail(id) {
      this.loading = true
      this.loadError = ''
      this.loadErrorCause = ''
      getContract(id)
        .then((response) => {
          this.fillForm(response.data || {})
          this.loading = false
          this.loadAttachments()
          this.loadChangeLogs()
        })
        .catch((err) => {
          this.loading = false
          const d = describeError(err)
          this.loadError = d.text
          this.loadErrorCause = d.cause
        })
    },
    /** 详情 → 表单（日期统一裁成 yyyy-MM-dd；标签按 手动/自动 拆开） */
    fillForm(data) {
      const tags = data.tags || []
      const manual = []
      const auto = []
      tags.forEach((tag) => {
        if (tag.auto === '1') {
          auto.push(tag)
        } else {
          manual.push(tag)
        }
      })
      // 已引用的物料可能已停用（不在启用清单一里）：用行项快照补出选项，避免下拉显示成裸 ID
      // ⚠ 必须先构 form.items（下面的 mergeProductOptions 读的就是它），否则物料选项会漏掉引用项
      const items = (data.items || []).map((item) => ({
        id: item.id,
        itemType: item.itemType,
        productId: item.productId,
        productCode: item.productCode,
        productName: item.productName,
        name: item.name,
        spec: item.spec,
        qty: item.qty === null || item.qty === undefined ? undefined : Number(item.qty),
        unitPrice: item.unitPrice === null || item.unitPrice === undefined ? undefined : Number(item.unitPrice),
        remark: item.remark
      }))
      this.form = {
        id: data.id,
        contractNo: data.contractNo,
        name: data.name,
        type: data.type,
        subjectCode: data.subjectCode,
        customerId: data.customerId,
        supplierId: data.supplierId,
        partyA: data.partyA,
        partyB: data.partyB,
        signDate: dayOnly(data.signDate),
        effectiveDate: dayOnly(data.effectiveDate),
        currency: data.currency || 'CNY',
        paidAmount: data.paidAmount === null || data.paidAmount === undefined ? undefined : Number(data.paidAmount),
        status: data.status,
        arrivalStatus: data.arrivalStatus,
        expectedArrivalDate: dayOnly(data.expectedArrivalDate),
        ownerName: data.ownerName,
        isFramework: data.isFramework === '1' ? '1' : '0',
        parentId: data.parentId || undefined,
        remark: data.remark,
        items: items,
        manualTagIds: manual.map((tag) => tag.id),
        autoTags: auto,
        relatedDocs: data.relatedDocs || [],
        delFlag: data.delFlag === '1' ? '1' : '0',
        hasWarranty: data.hasWarranty === '1' ? '1' : '0',
        warrantyStart: dayOnly(data.warrantyStart),
        warrantyMonths: data.warrantyMonths === null || data.warrantyMonths === undefined ? undefined : Number(data.warrantyMonths),
        warrantyAmount:
          data.warrantyAmount === null || data.warrantyAmount === undefined ? undefined : Number(data.warrantyAmount),
        warrantyRate: data.warrantyRate === null || data.warrantyRate === undefined ? undefined : Number(data.warrantyRate),
        warrantyNote: data.warrantyNote,
        warrantyReleased: data.warrantyReleased === '1' ? '1' : '0',
        warrantyReleaseDate: dayOnly(data.warrantyReleaseDate)
      }
      // 详情是"权威集合"：选项里要有它引用的物料（已停用的、排在前 500 条之外的）
      this.productOptions = this.mergeProductOptions(this.productOptions)
      // 自己不能作为自己的父框架（详情异步回来，fetchFrameworks 可能先于它完成）
      this.frameworkOptions = this.frameworkOptions.filter((row) => row.id !== data.id)
    },
    loadAttachments() {
      if (!this.form.id || !this.canReadAttachment) {
        this.attachments = []
        this.loadAttachmentLimits()
        return
      }
      this.attachmentLoading = true
      this.attachmentError = ''
      listAttachment({ objectType: OBJECT_TYPE_CONTRACT, objectId: this.form.id })
        .then((response) => {
          this.attachments = response.data || []
          this.attachmentLoading = false
        })
        .catch((err) => {
          this.attachmentLoading = false
          this.attachments = []
          const d = describeError(err)
          this.attachmentError = '附件列表加载失败：' + d.text
        })
      this.loadAttachmentLimits()
    },
    /** 限制口径（20MB / 后缀白名单）只认接口，不在前端写第二份常量 */
    loadAttachmentLimits() {
      if (!this.canReadAttachment) {
        this.attachmentLimits = {}
        return
      }
      getAttachmentObjectTypes()
        .then((response) => {
          this.attachmentLimits = {
            registered: response.registered || [],
            plannedB4: response.plannedB4 || [],
            maxSizeBytes: response.maxSizeBytes,
            maxSizeMb: response.maxSizeMb,
            extensions: response.extensions || []
          }
        })
        .catch((err) => {
          this.attachmentLimits = {}
          const d = describeError(err)
          this.attachmentError = '附件限制口径加载失败：' + d.text
        })
    },
    loadChangeLogs() {
      if (!this.form.id) {
        this.changeLogs = []
        this.changeLogTotal = 0
        return
      }
      this.changeLogLoading = true
      this.changeLogError = ''
      listContractChangeLogs(this.form.id, { pageNum: 1, pageSize: CHANGE_LOG_PAGE_SIZE })
        .then((response) => {
          this.changeLogs = response.rows || []
          this.changeLogTotal = response.total || 0
          this.changeLogLoading = false
        })
        .catch((err) => {
          this.changeLogLoading = false
          this.changeLogs = []
          this.changeLogTotal = 0
          const d = describeError(err)
          this.changeLogError = '变更历史加载失败：' + d.text
        })
    },
    /* ==================== 行项 ==================== */
    handleAddItem() {
      this.form.items.push({ itemType: undefined, productId: undefined, name: undefined, spec: undefined, qty: undefined, unitPrice: undefined, remark: undefined })
    },
    handleRemoveItem(index) {
      this.form.items.splice(index, 1)
    },
    /** 选物料后按后端回落规则补全名称/规格（留空时），让用户看到真正会落库的值 */
    handleProductChange(row) {
      const product = this.productOptions.find((item) => item.id === row.productId)
      if (!product) {
        return
      }
      // 记下物料快照：物料清单之后若被刷新（该物料已停用/不在前 500 条），下拉仍要有名字可显示
      row.productCode = product.code
      row.productName = product.name
      if (!row.name) {
        row.name = product.name
      }
      if (!row.spec) {
        row.spec = product.spec
      }
    },
    productLabel(item) {
      const parts = [item.code, item.name]
      const label = parts.filter(Boolean).join(' ')
      return item.spec ? label + '（' + item.spec + '）' : label
    },
    lineTotalText(qty, unitPrice) {
      return contractRules.lineTotalText(qty, unitPrice)
    },
    /** 与后端 applyItems 一致：完全空白的行不提交（避免"点了添加行但没填"卡住校验） */
    effectiveItems() {
      return (this.form.items || []).filter((row) => {
        return !!(
          row.productId ||
          row.itemType ||
          row.name ||
          row.spec ||
          row.qty ||
          row.unitPrice ||
          row.remark
        )
      })
    },
    contractAmountValue() {
      return Number(this.amountSumText)
    },
    /* ==================== 质保 ==================== */
    /** 金额 ↔ 比例联动：改金额就补比例，改比例就补金额（都按后端同口径换算） */
    handleWarrantyAmountChange() {
      const computed = contractRules.rateOfAmountText(this.amountSumText, this.form.warrantyAmount)
      if (computed) {
        this.form.warrantyRate = Number(computed)
      }
    },
    handleWarrantyRateChange() {
      const computed = contractRules.amountOfRateText(this.amountSumText, this.form.warrantyRate)
      if (computed) {
        this.form.warrantyAmount = Number(computed)
      }
    },
    handleReleaseWarranty() {
      this.$modal
        .confirm('是否确认释放本合同的质量保证金？<br/>释放后「质保是否释放」置为已释放，并记录释放日期与变更历史。')
        .then(() => {
          this.releaseLoading = true
          return releaseWarranty(this.form.id)
        })
        .then(() => {
          this.releaseLoading = false
          this.$modal.msgSuccess('质保已释放')
          this.loadDetail(this.form.id)
          this.$emit('refresh')
        })
        .catch((err) => {
          this.releaseLoading = false
          this.reportActionError(err)
        })
    },
    /* ==================== 框架 ==================== */
    handleFrameworkChange() {
      if (this.form.isFramework === '1') {
        // 互斥：框架合同不能再挂到别的框架下（后端会拒：「框架合同不能再挂到其他框架下」）
        this.form.parentId = undefined
      }
    },
    handleParentChange() {
      if (this.form.parentId) {
        this.form.isFramework = '0'
      }
    },
    /* ==================== 编号预览 ==================== */
    handlePreviewNo() {
      if (!this.form.type || !this.form.subjectCode) {
        this.nextNoError = '请先选择合同类型与我方主体，再取号预览'
        return
      }
      this.nextNoLoading = true
      this.nextNoError = ''
      nextContractNo({
        type: this.form.type,
        subjectCode: this.form.subjectCode,
        referenceDay: this.form.signDate || undefined
      })
        .then((response) => {
          this.nextNoLoading = false
          // ⚠ 返回值在 msg 上（AjaxResult.success(String) 命中 success(String msg) 重载）
          this.nextNoPreview = response.msg || ''
        })
        .catch((err) => {
          this.nextNoLoading = false
          this.nextNoPreview = ''
          const d = describeError(err)
          this.nextNoError = d.text
        })
    },
    /* ==================== 提交 ==================== */
    /**
     * 组装请求体：字段名与后端 `CtmsContract` 逐字一致。
     * 金额相关一律给**定点字符串**（Jackson 按 BigDecimal 精确解析），不用 JS 浮点数字。
     * 到期日 `warrantyEnd` 刻意不提交：服务端按「生效日 + 期限」重算，前端传值不参与。
     */
    buildPayload() {
      const form = this.form
      const items = this.effectiveItems().map((row) => ({
        itemType: row.itemType || undefined,
        productId: row.productId,
        name: row.name || undefined,
        spec: row.spec || undefined,
        qty: contractRules.roundToText(row.qty, contractRules.SCALE_QTY) || '0',
        unitPrice: contractRules.roundToText(row.unitPrice, contractRules.SCALE_PRICE) || '0',
        remark: row.remark || undefined
      }))
      const payload = {
        id: form.id || undefined,
        contractNo: form.contractNo ? String(form.contractNo).trim() : undefined,
        name: form.name,
        type: form.type,
        subjectCode: form.subjectCode || undefined,
        customerId: form.customerId || undefined,
        supplierId: form.supplierId || undefined,
        partyA: form.partyA || undefined,
        partyB: form.partyB || undefined,
        signDate: form.signDate || undefined,
        effectiveDate: form.effectiveDate || undefined,
        currency: form.currency || undefined,
        paidAmount: contractRules.roundToText(form.paidAmount, contractRules.SCALE_AMOUNT) || undefined,
        status: form.status || undefined,
        arrivalStatus: form.arrivalStatus || undefined,
        expectedArrivalDate: form.expectedArrivalDate || undefined,
        ownerName: form.ownerName || undefined,
        isFramework: form.isFramework,
        // parentId 的合并语义：null＝保持库内原值，''＝解绑。
        // 没选父框架时一律传 ''：对"本来就没有父"的合同是幂等的 no-op，对"刚清空父"的合同正好是解绑。
        parentId: form.parentId ? form.parentId : '',
        remark: form.remark || undefined,
        hasWarranty: form.hasWarranty,
        // 只有启用质保才提交质保字段（关闭质保时服务端会清空 7 个字段）
        warrantyStart: form.hasWarranty === '1' ? form.warrantyStart || undefined : undefined,
        warrantyMonths: form.hasWarranty === '1' ? form.warrantyMonths || undefined : undefined,
        warrantyAmount:
          form.hasWarranty === '1' ? contractRules.roundToText(form.warrantyAmount, contractRules.SCALE_AMOUNT) || undefined : undefined,
        warrantyRate:
          form.hasWarranty === '1' ? contractRules.roundToText(form.warrantyRate, contractRules.SCALE_RATE) || undefined : undefined,
        warrantyNote: form.hasWarranty === '1' ? form.warrantyNote || undefined : undefined,
        // 行项全量提交（服务端 delete + batchInsert 全量替换）；空数组＝清空行项并把金额算成 0.00
        items: items,
        tagIds: (form.manualTagIds || []).slice()
      }
      return payload
    },
    submitForm() {
      this.$refs['form'].validate((valid) => {
        if (!valid) {
          this.$modal.msgError('表单校验未通过：请检查标红项（行项必须选物料档案）')
          return
        }
        const payload = this.buildPayload()
        const isEdit = !!payload.id
        this.submitLoading = true
        const job = isEdit ? updateContract(payload) : addContract(payload)
        job
          .then(() => {
            this.submitLoading = false
            this.$modal.msgSuccess(isEdit ? '修改成功' : '新增成功')
            this.visible = false
            this.$emit('refresh')
          })
          .catch((err) => {
            this.submitLoading = false
            const d = describeError(err)
            this.$modal.msgError(d.text)
          })
      })
    },
    /* ==================== 附件 ==================== */
    /** 上传前用**接口给的**口径预检（服务端仍会再判一次；这里只是让用户更快知道原因） */
    beforeUpload(file) {
      const limits = this.attachmentLimits || {}
      if (!limits.maxSizeBytes) {
        this.$modal.msgError('附件限制口径未取到（/ctms/attachment/object-types），请刷新后重试')
        return false
      }
      if (file.size > limits.maxSizeBytes) {
        this.$modal.msgError('单个附件不能超过 ' + limits.maxSizeMb + 'MB（当前 ' + humanSize(file.size) + '）')
        return false
      }
      const extensions = limits.extensions || []
      const name = file.name || ''
      const dot = name.lastIndexOf('.')
      const extension = dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''
      if (extensions.indexOf(extension) < 0) {
        this.$modal.msgError('不支持的文件后缀「' + (extension || '(无后缀)') + '」；允许：' + extensions.join(' / '))
        return false
      }
      if (limits.registered && limits.registered.indexOf(OBJECT_TYPE_CONTRACT) < 0) {
        this.$modal.msgError('对象类型 contract 未注册附件，请联系管理员检查注册表')
        return false
      }
      return true
    },
    handleUploadRequest(param) {
      this.uploadLoading = true
      this.attachmentError = ''
      uploadAttachment(OBJECT_TYPE_CONTRACT, this.form.id, param.file)
        .then(() => {
          this.uploadLoading = false
          this.$modal.msgSuccess('上传成功')
          this.loadAttachments()
          this.loadChangeLogs()
        })
        .catch((err) => {
          this.uploadLoading = false
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    handleDownloadAttachment(row) {
      this.attachmentDownloadingId = row.id
      downloadAttachment(row.id)
        .then((data) => {
          this.attachmentDownloadingId = ''
          if (!blobValidate(data)) {
            // 后端把失败写成了 JSON（HTTP 仍是 200，例如「附件文件不存在」）：
            // 直接抛错走外层 catch，绝不能把 JSON 当文件存下来（否则用户拿到一个打不开的"坏文件"）。
            // ⚠ 刻意不在这里再挂一条 `data.text().then(...)`：那是"链中链"，
            //   看起来没有 catch（外层 catch 不在同一条成员链上），而且会把简单逻辑绕复杂。
            throw new Error('附件下载失败：服务端返回的是错误信息而不是文件，请重试或联系管理员')
          }
          saveAs(new Blob([data]), row.fileName || 'attachment')
          return null
        })
        .catch((err) => {
          this.attachmentDownloadingId = ''
          const d = describeError(err)
          this.$modal.msgError(d.text)
        })
    },
    handleDeleteAttachment(row) {
      this.$modal
        .confirm('是否确认删除附件「' + row.fileName + '」？<br/>删除后该附件不能再下载（元数据保留用于审计）。')
        .then(() => {
          this.attachmentDeletingId = row.id
          return delAttachment(row.id)
        })
        .then(() => {
          this.attachmentDeletingId = ''
          this.$modal.msgSuccess('删除成功')
          this.loadAttachments()
          this.loadChangeLogs()
        })
        .catch((err) => {
          this.attachmentDeletingId = ''
          this.reportActionError(err)
        })
    },
    humanSize(bytes) {
      return humanSize(bytes)
    },
    /* ==================== 展示小工具 ==================== */
    fieldLabel(name) {
      if (!name) {
        return '—'
      }
      return CHANGE_LOG_FIELD_LABELS[name] || name
    },
    /**
     * 动作类请求的收尾：`$confirm` 的「取消」reject 与真正的接口失败都落在这里。
     * 不能写成空 catch（静默吞掉失败），也不能把「取消」当失败弹错。
     */
    reportActionError(err) {
      if (!err) {
        return
      }
      if (typeof err === 'string' && (err === 'cancel' || err === 'close')) {
        return
      }
      this.$modal.msgError(describeError(err).text)
    }
  }
}
</script>

<style scoped>
/* 只写令牌，不写裸色值（DEV-ENV §6.9/§6.11） */
.contract-detail {
  min-height: 260px;
  padding-right: 6px;
}
.section-title {
  margin: 4px 0 10px;
  padding-left: 8px;
  font-weight: 600;
  color: var(--oa-color-ink);
  border-left: 3px solid var(--oa-color-primary);
}
.field-tip {
  margin: -12px 0 10px 104px;
  line-height: 18px;
}
.tip-text {
  margin-left: 6px;
  color: var(--oa-color-ink-subtle);
  font-size: 12px;
}
.text-muted {
  color: var(--oa-color-ink-subtle);
}
.text-danger {
  margin-left: 6px;
  color: var(--oa-color-error);
  font-size: 12px;
}
.mr4 {
  margin-right: 4px;
}
.item-table {
  margin-bottom: 8px;
}
.amount-line {
  margin-bottom: 10px;
  padding: 6px 10px;
  background: var(--oa-color-surface-1);
  border-radius: var(--oa-radius-sm);
  text-align: right;
}
.amount-label {
  margin-right: 8px;
  color: var(--oa-color-ink-muted);
}
.amount-value {
  font-weight: 600;
  color: var(--oa-color-primary);
}
.attachment-bar {
  margin-bottom: 8px;
}
.attachment-upload {
  display: inline-block;
  margin-right: 8px;
}
</style>
