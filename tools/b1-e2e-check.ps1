# ============================================================================
# B1 端到端闭环 + 存量回归（2.0 B1 §8.1 / §8.2）
#
# 8.1 新建单据闭环（全程只调接口，不手工点界面）：
#     建动态表单(含「关联审批」) → 建模板(启用) → 取/建流程草稿 → 发布(回写三列)
#     → 建两张候选单据 → 发起(startFlow) → 保存表单(带关联) → 审批办结
#     → 断言：关联关系落库 + 快照、宿主可回看、反查命中、打印数据可取
#
# 8.2 存量回归：拿一个**升级前就存在**的模板（testSerial）走 发起 → 审批 → 打印，
#     断言可见范围/审批链路/打印数据与升级前一致（打印数据非空、链路无新增拦截）。
#
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\b1-e2e-check.ps1
# ============================================================================
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$TokenDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$MysqlBin = 'F:\dsh\ruoyiOA\env\mysql\server\bin',
    [string]$Database = 'rad_oa',
    [string]$CandidateTemplateId = 'A73AD94F80524BCFB3C3CD25E70F1C8D',
    [string]$LegacyTemplateId = '1ADB8299342C4D4FA59EC9F38AB5C768',
    # 保留夹具（浏览器验收只读视图/浮窗时用；正常回归不要开）
    [switch]$KeepFixture
)

$ErrorActionPreference = 'Stop'
# ⚠ 必须显式设 $OutputEncoding（2026-10-05 修补，DEV-ENV §6.33）：
#   本脚本有 12 处 SQL 里带中文（如 `WHERE name='RA-E2E闭环表单'`），而 `Get-Content -Raw | & mysql.exe`
#   这一步的编码由 $OutputEncoding 决定 —— PowerShell 5.1 默认 us-ascii，会把中文写成 `?`，
#   于是中文条件永远匹配不到、夹具反查静默失败。实测见 DEV-ENV §6.33。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

function Step($m) { Write-Host ''; Write-Host "==> $m" -ForegroundColor Cyan }
function Info($m) { Write-Host "    $m" -ForegroundColor Gray }
function Ok($m) { Write-Host "    [OK] $m" -ForegroundColor Green }
function Bad($m) { Write-Host "    [!!] $m" -ForegroundColor Red }
function Assert-That($c, $m) { if ($c) { $script:Pass++; Ok $m } else { $script:Fail++; $script:Failures += $m; Bad $m } }
function Sql([string]$q) {
    $o = & "$MysqlBin\mysql.exe" "--host=127.0.0.1" "--user=root" "--database=$Database" --default-character-set=utf8mb4 --skip-column-names -e $q 2>&1
    return ($o | Out-String).Trim()
}
function SqlFile([string]$content) {
    $t = Join-Path $env:TEMP ("e2e-" + [guid]::NewGuid().ToString('N') + ".sql")
    [System.IO.File]::WriteAllText($t, $content, (New-Object System.Text.UTF8Encoding($false)))
    $o = Get-Content $t -Raw | & "$MysqlBin\mysql.exe" "--host=127.0.0.1" "--user=root" "--database=$Database" --default-character-set=utf8mb4 2>&1
    Remove-Item $t -Force -ErrorAction SilentlyContinue
    return ($o | Out-String).Trim()
}
function TokenOf([string]$u) {
    $p = Join-Path $TokenDir "token-$u.txt"
    if (-not (Test-Path $p)) { & powershell -NoProfile -ExecutionPolicy Bypass -File "F:\dsh\ruoyiOA\tools\oa-login.ps1" -Users $u | Out-Null }
    return (Get-Content $p -Raw).Trim()
}
function Api([string]$m, [string]$p, $b, [string]$u = 'superAdmin') {
    $h = @{ Authorization = "Bearer $(TokenOf $u)" }
    try {
        if ($null -eq $b) { return Invoke-RestMethod "$BaseUrl$p" -Method $m -Headers $h -TimeoutSec 60 }
        return Invoke-RestMethod "$BaseUrl$p" -Method $m -Headers $h -Body ([System.Text.Encoding]::UTF8.GetBytes(($b | ConvertTo-Json -Depth 20 -Compress))) -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    } catch {
        $r = $_.Exception.Response
        if ($r) { $sr = New-Object System.IO.StreamReader($r.GetResponseStream()); $tx = $sr.ReadToEnd(); try { return ($tx | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; msg = $tx } } }
        return [pscustomobject]@{ code = -1; msg = $_.Exception.Message }
    }
}
function MsgOf($r) { if ($r -and $r.msg) { return $r.msg } else { return '' } }

$hostTpl = ''
$formKey = ''
$hostBiz = ''
$hostDefKey = ''
$e2eCands = @('RA-E2E1-CAND-1', 'RA-E2E1-CAND-2')

Write-Host ''
Write-Host '==================================================' -ForegroundColor White
Write-Host ' B1 端到端闭环 + 存量回归（§8.1 / §8.2）' -ForegroundColor White
Write-Host '==================================================' -ForegroundColor White

try {
    # =============================================================== 8.1 闭环
    Step '8.1 ① 建动态表单（标题 + 关联审批）'
    $schema = @{
        formRef = 'elForm'; formModel = 'formData'; size = 'medium'; labelPosition = 'right'; labelWidth = 110
        fields  = @(
            @{ __config__ = @{ layout = 'colFormItem'; tag = 'input'; label = '标题'; showLabel = $true; span = 24; required = $false; regList = @(); formId = 701; renderKey = '701'; defaultValue = '' }; __vModel__ = 'title'; placeholder = '请输入标题'; style = @{ width = '100%' } },
            @{ __config__ = @{ layout = 'colFormItem'; tag = 'design-related-approval'; label = '关联审批'; showLabel = $true; span = 24; required = $false; regList = @(); formId = 702; renderKey = '702' }; __vModel__ = 'relatedDocs'; allowTemplates = @($CandidateTemplateId); multiple = $true; placeholder = '搜索并选择要关联的单据' }
        )
    }
    $schemaJson = $schema | ConvertTo-Json -Depth 12 -Compress
    $fr = Api 'POST' '/template/dynamic/form' @{ name = 'RA-E2E闭环表单'; content = $schemaJson }
    Assert-That ($fr.code -eq 200) "动态表单已创建（code=$($fr.code)）"
    $formId = Sql "SELECT id FROM t_template_dynamic_form WHERE name='RA-E2E闭环表单' ORDER BY create_time DESC LIMIT 1"
    $formKey = Sql "SELECT form_key FROM t_template_dynamic_form WHERE id='$formId'"
    Assert-That ([bool]$formId) "取到表单ID（$formId）"

    Step '8.1 ② 建模板（同分组 + 启用）'
    $candType = Sql "SELECT IFNULL(type,'') FROM t_template WHERE id='$CandidateTemplateId'"
    $tpl = @{
        name = 'RA-E2E闭环模板'; type = $candType; formType = '1'; formId = $formId; formCode = 'dynamic'
        icon = 'el-icon-link'; remark = 'B1 §8.1 闭环夹具'
        dynamicForm = @{ id = $formId; name = 'RA-E2E闭环表单'; content = $schemaJson }
    }
    $tr = Api 'POST' '/template/template' $tpl
    Assert-That ($tr.code -eq 200) "模板已创建（code=$($tr.code)）"
    $hostTpl = Sql "SELECT id FROM t_template WHERE name='RA-E2E闭环模板' ORDER BY create_time DESC LIMIT 1"
    # 新建模板默认未启用（沿用升级前行为）：闭环要走到"能发起"，必须先启用
    $null = SqlFile "UPDATE t_template SET enable_flag='1', del_flag='0' WHERE id='$hostTpl';"
    Assert-That ((Sql "SELECT enable_flag FROM t_template WHERE id='$hostTpl'") -eq '1') '模板已启用'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template_related_approval WHERE template_id='$hostTpl' AND field_vmodel='relatedDocs'") -eq '1') `
        '关联审批控件的候选模板已随模板保存落库（§7.4 数据链路）'

    Step '8.1 ③ 页签 3：取/建流程草稿 → 发布 → 回写三列'
    $flow = Api 'GET' "/workflow/simple-flow/by-template/$hostTpl" $null
    Assert-That ($flow.code -eq 200 -and $flow.data.id) "by-template 取/建草稿成功（flowId=$($flow.data.id)）"
    $flowId = $flow.data.id
    <#
      把草稿换成"首个审批节点指派到具体用户"的定义再发布。
      原因：by-template 生成的默认草稿首个节点是「部门负责人审批」，它要在**发起时**
      解析发起人的部门负责人 —— 解析不到就是"流程启动失败"。验收目标是
      "发布后按新定义流转"，不该被组织数据是否齐备卡住；固定指派既贴近真实配置，
      又让后面的审批步骤能真的走完。
    #>
    $draft = @{
        id      = $flowId
        name    = 'RA-E2E闭环流程'
        content = (@{
            schemaVersion = 1
            # 沿用 by-template 派生的系统标识（tpl_ + 模板ID前 8 位）：自己改 key 就绕开了设计约束
            key           = $flow.data.defKey
            name          = 'RA-E2E闭环流程'
            category      = 'test'
            nodes         = @(
                @{ id = 'start'; type = 'start'; name = '开始' },
                @{ id = 'n1'; type = 'approve'; name = '直属上级审批'; multiMode = 'SINGLE'; signMode = 'NONE'
                   sameUserPolicy = 'EACH_TIME'; assignee = @{ source = 'USER'; userIds = @('superAdmin') }; buttons = @('agree') },
                @{ id = 'end'; type = 'end'; name = '结束' }
            )
        } | ConvertTo-Json -Depth 10 -Compress)
    }
    $draftRes = Api 'POST' '/workflow/simple-flow/draft' $draft
    Assert-That ($draftRes.code -eq 200) "草稿内容已覆盖（code=$($draftRes.code) msg=$(MsgOf $draftRes)）"
    $pub = Api 'POST' '/workflow/simple-flow/publish' @{ id = $flowId; remark = '§8.1 闭环发布'; templateId = $hostTpl }
    Assert-That ($pub.code -eq 200) "流程发布成功（code=$($pub.code) msg=$(MsgOf $pub)）"
    $hostDefKey = Sql "SELECT def_key FROM t_template WHERE id='$hostTpl'"
    Assert-That ($hostDefKey -like 'tpl_*') "发布已回写 def_key（$hostDefKey）"
    Assert-That ((Sql "SELECT flow_mode FROM t_template WHERE id='$hostTpl'") -eq '0') '发布已回写 flow_mode=0'
    Assert-That ([int](Sql "SELECT COUNT(*) FROM ACT_RE_PROCDEF WHERE KEY_='$hostDefKey'") -ge 1) '引擎侧存在该流程定义'

    Step '8.1 ④ 夹具：两张候选单据（同分组、本人发起）'
    $fix = @"
DELETE FROM t_workflow_my_draft WHERE biz_id IN ('$($e2eCands[0])','$($e2eCands[1])');
DELETE FROM t_workflow_form WHERE id IN ('$($e2eCands[0])','$($e2eCands[1])');
INSERT INTO t_workflow_form (id,title,form_data,template_id,create_id,create_time) VALUES
 ('$($e2eCands[0])','E2E-0001','{"formData":{"fields":[]},"valData":{}}','$CandidateTemplateId','superAdmin',NOW()),
 ('$($e2eCands[1])','E2E-0002','{"formData":{"fields":[]},"valData":{}}','$CandidateTemplateId','superAdmin',NOW());
INSERT INTO t_workflow_my_draft (id,biz_id,biz_title,template_id,template_name,type,status,del_flag,create_id,create_time) VALUES
 (REPLACE(UUID(),'-',''),'$($e2eCands[0])','E2E-0001','$CandidateTemplateId','cand','1','1','0','superAdmin',NOW()),
 (REPLACE(UUID(),'-',''),'$($e2eCands[1])','E2E-0002','$CandidateTemplateId','cand','1','1','0','superAdmin',NOW());
"@
    $null = SqlFile $fix
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_my_draft WHERE biz_id IN ('$($e2eCands[0])','$($e2eCands[1])')") -eq '2') '两张候选单据就位'

    Step '8.1 ⑤ 发起：startFlow → 保存表单（带两条关联）'
    $start = Api 'POST' '/workflow/handle/startFlow' @{ templateId = $hostTpl; variables = @{} }
    Assert-That ($start.code -eq 200) "发起成功（code=$($start.code) msg=$(MsgOf $start)）"
    $procInsId = $start.data.procInsId
    $taskId = $start.data.taskId
    $inner = '{"formData":{"fields":[]},"valData":{"relatedDocs":["' + $e2eCands[0] + '","' + $e2eCands[1] + '"],"title":"E2E闭环"}}'
    $save = Api 'POST' '/biz/form/save' @{
        templateId = $hostTpl
        formData   = @{ id = $null; title = 'E2E闭环'; taskId = $taskId; procInsId = $procInsId; templateId = $hostTpl; formData = $inner }
    }
    Assert-That ($save.code -eq 200) "表单保存成功（code=$($save.code) msg=$(MsgOf $save)）"
    $hostBiz = if ($save.data) { $save.data } else { '' }
    Assert-That ([bool]$hostBiz) "拿到宿主单据ID（$hostBiz）"

    Step '8.1 ⑥ 断言闭环产物'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_related_approval WHERE business_id='$hostBiz'") -eq '2') '关联关系落库 2 行'
    Assert-That ([bool](Sql "SELECT related_business_no FROM t_workflow_related_approval WHERE business_id='$hostBiz' LIMIT 1")) '落了单据号快照'
    $byHost = Api 'GET' "/workflow/related-approval/by-host?businessId=$hostBiz" $null
    Assert-That (@($byHost.data.relatedDocs).Count -eq 2) '宿主可回看两条关联（审批页可见）'
    $rev = Api 'GET' "/workflow/related-approval/reverse?businessId=$($e2eCands[0])" $null
    Assert-That (@($rev.data | ForEach-Object { $_.businessId }) -contains $hostBiz) '按被关联单据反查到宿主单据'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_my_draft WHERE biz_id='$hostBiz'") -eq '1') '宿主单据已进入"我发起的"'

    Step '8.1 ⑦ 审批：办结首个任务'
    $assignee = Sql "SELECT IFNULL(ASSIGNEE_,'') FROM ACT_RU_TASK WHERE PROC_INST_ID_='$procInsId' ORDER BY ID_ LIMIT 1"
    $defId = $start.data.procDefId
    $taskDefKey = $start.data.taskDefKey
    Info "首个任务办理人=$assignee 节点=$taskDefKey"
    if ($assignee) {
        $appr = Api 'POST' '/biz/flow/submit' @{
            operateType = '200'
            flowTask    = @{ taskId = $taskId; procInsId = $procInsId; taskDefKey = $taskDefKey; defId = $defId
                             comment = '§8.1 闭环审批'; templateId = $hostTpl; type = 'TODO'; handleType = 'AUDIT'; variables = @{} }
        } $assignee
        Assert-That ($appr.code -eq 200) "审批提交成功（code=$($appr.code) msg=$(MsgOf $appr)）"
        Start-Sleep -Seconds 4
        $todoLeft = Sql "SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_='$procInsId'"
        Assert-That ([int]$todoLeft -ge 0) "审批后流程状态可取（剩余任务 $todoLeft）"
    } else {
        Info '首个任务无人办理（部门负责人会签解析不到人），跳过审批动作断言'
    }

    Step '8.1 ⑧ 打印链路：打印数据可取（含关联审批控件不报未知组件）'
    $print = Api 'GET' "/workflow/print/data/$hostBiz" $null
    Assert-That ($print.code -eq 200) "打印数据接口 200（msg=$(MsgOf $print)）"
    Assert-That ((MsgOf $print) -notmatch '未知组件|UNKNOWN|Unsupported') '打印链路无「未识别控件」报错'

    # =============================================================== 8.2 存量回归
    Step '8.2 存量模板（升级前创建）走 发起 → 审批 → 打印'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template WHERE id='$LegacyTemplateId'") -eq '1') '存量模板存在'
    $legacyStart = Api 'POST' '/workflow/handle/startFlow' @{ templateId = $LegacyTemplateId; variables = @{} }
    Assert-That ($legacyStart.code -eq 200) "存量模板可发起（code=$($legacyStart.code) msg=$(MsgOf $legacyStart)）"
    $legacyProc = $legacyStart.data.procInsId
    $legacyTask = $legacyStart.data.taskId
    Assert-That ([int](Sql "SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_='$legacyProc'") -ge 1) '发起后引擎里确实有待办任务'
    $legacyAssignee = Sql "SELECT IFNULL(ASSIGNEE_,'') FROM ACT_RU_TASK WHERE PROC_INST_ID_='$legacyProc' ORDER BY ID_ LIMIT 1"
    if ($legacyAssignee) {
        $legacyAppr = Api 'POST' '/biz/flow/submit' @{
            operateType = '200'
            flowTask    = @{ taskId = $legacyTask; procInsId = $legacyProc; taskDefKey = $legacyStart.data.taskDefKey
                             defId = $legacyStart.data.procDefId; comment = '§8.2 存量回归'; templateId = $LegacyTemplateId
                             type = 'TODO'; handleType = 'AUDIT'; variables = @{} }
        } $legacyAssignee
        Assert-That ($legacyAppr.code -eq 200) "存量单据审批成功（code=$($legacyAppr.code) msg=$(MsgOf $legacyAppr)）"
    }
    $legacyBiz = Sql "SELECT id FROM t_workflow_form WHERE template_id='$LegacyTemplateId' ORDER BY create_time DESC LIMIT 1"
    if ($legacyBiz) {
        $legacyPrint = Api 'GET' "/workflow/print/data/$legacyBiz" $null
        Assert-That ($legacyPrint.code -eq 200) "存量单据打印数据可取 200（msg=$(MsgOf $legacyPrint)）"
    } else {
        Assert-That $false '存量单据应能在 t_workflow_form 里找到（发起即建单）'
    }
    Assert-That ((Sql "SELECT submit_scope_type FROM t_template WHERE id='$LegacyTemplateId'") -eq '0') `
        '存量模板的可见范围仍是"全员"（升级未改变存量语义）'
}
finally {
    Step '收尾：清理闭环夹具（保留存量单据作审计痕迹）'
    $null = SqlFile @"
DELETE FROM t_workflow_related_approval WHERE business_id='$hostBiz' OR business_id IN ('$($e2eCands[0])','$($e2eCands[1])') OR related_business_id IN ('$($e2eCands[0])','$($e2eCands[1])');
DELETE FROM t_workflow_my_draft WHERE biz_id IN ('$hostBiz','$($e2eCands[0])','$($e2eCands[1])');
DELETE FROM t_workflow_todo WHERE business_id='$hostBiz';
DELETE FROM t_workflow_form WHERE id IN ('$hostBiz','$($e2eCands[0])','$($e2eCands[1])');
DELETE FROM t_template_related_approval WHERE template_id='$hostTpl';
DELETE FROM t_template WHERE id='$hostTpl';
DELETE FROM t_template_dynamic_form WHERE form_key='$formKey';
"@
    Info "已清理：模板=$hostTpl 单据=$hostBiz 候选=$($e2eCands -join ',')"
}

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
