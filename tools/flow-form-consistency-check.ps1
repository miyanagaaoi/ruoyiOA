<#
================================================================================
 flow-form-consistency-check.ps1 —— 「模板 form_id ↔ 流程 content.formId」一致性巡检 + PRD V-8 反向校验的接口验收
--------------------------------------------------------------------------------
 为什么需要它（缺陷背景，见 doc\缺陷-流程条件字段与关联表单错位.md §4.5）：
   动态表单保存 = 停用旧版本行 + 插一条新版行（**换 id**）。后端会把模板
   t_template.form_id 改指到新版本（TemplateMapper.xml#repointFormId），
   而 t_flow_simple.content.formId **不会跟随** ⇒ "模板跟着新版走、流程留在旧版"，
   运行期条件字段找不到（V-8 误报 / 提交报错）。当时靠人工对齐数据修掉，代码里没有防线。
   HANDOFF §11 第 8/9 行要的两件事，本脚本就是它们的可复跑验收：
     8) 一致性检查：GET /workflow/simple-flow/form-consistency（只读巡检接口）
     9) PRD V-8 反向校验：POST/PUT /template/dynamic/form 保存表单时的阻断与告警

 本脚本做三件事（**全部可重复执行**）：
   ① 只读：打真实数据的巡检接口（不写任何东西）；
   ② 探针：临时造一组 T16PROBE- 夹具（动态表单 ×2 / 流程 ×1 / 模板 ×1），
      验证"构造错位 → 检出""字段失效 → 阻断且不留半成品""字段仍有效 → 放行但告警"
      "把流程 content.formId 改指新版 → 巡检转干净"；探针**从不发布流程**，因此不产生任何 ACT_* 行；
   ③ 收尾：删除探针夹具（默认开启），并复核巡检结果回到基线（证明确实没污染真实数据）。

 断言口径（与 DEV-ENV §6.56 同族，务必照抄）：
   RuoYi 的认证失败/业务失败**都不一定是 HTTP 状态码**：认证失败是 HTTP 200 + body code=401；
   ServiceException 是 HTTP 200 + body code=500。所以每次调用都必须判 body 里的 code，
   失败时把 **code + 原始 body** 打出来，不能只看"有没有抛异常"。

 用法：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\flow-form-consistency-check.ps1
   powershell ... -File .\tools\flow-form-consistency-check.ps1 -KeepProbeFixtures   # 留夹具复核
   powershell ... -File .\tools\flow-form-consistency-check.ps1 -OnlyReadOnly        # 只跑只读巡检

 前置：MySQL(3306) / Redis(6379) / RabbitMQ(5672) / 后端(8080) 已启动，
       且 .cache\token-superAdmin.txt 里是有效 token（见 tools\oa-login.ps1）。

 @author 二开（t16）
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl  = 'http://localhost:8080',
    [string]$MySqlCli = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database = 'rad_oa',
    [string]$Out      = 'F:\dsh\ruoyiOA\.cache\flow-form-consistency-check.txt',
    [switch]$KeepProbeFixtures,
    [switch]$OnlyReadOnly
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

function Say  ($m) { Write-Host $m; $m | Out-File -FilePath $Out -Encoding utf8 -Append }
function Step ($m) { Say ''; Say "==> $m" }
function Ok   ($m) { Say "    [OK] $m" }
function Bad  ($m) { Say "    [!!] $m" }
function Assert-That([bool]$cond, [string]$desc) {
    if ($cond) { Ok $desc; $script:Pass++ } else { Bad $desc; $script:Fail++; $script:Failures += $desc }
}

# ------------------------------------------------------------------ 基础工具

function Sql([string]$q) {
    $o = & $MySqlCli '--host=127.0.0.1' '--user=root' "-D" $Database '--batch' '--skip-column-names' `
                     '--default-character-set=utf8mb4' -e $q 2>&1
    return @($o | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}
# ⚠ 取单值必须走 Sql1：PowerShell 会把"单元素数组"摊平成字符串，
#   于是 (Sql "...")[0] 取到的是**第一个字符**（'T16PROBE-FRM-0003' → 'T'）。
function Sql1([string]$q) {
    $r = Sql $q
    return ($r | Select-Object -First 1)
}
function TokenOf([string]$user) {
    $f = Join-Path $CacheDir "token-$user.txt"
    if (-not (Test-Path $f)) { throw "缺少 token 文件：$f（先跑 tools\oa-login.ps1）" }
    return (Get-Content $f -Raw).Trim()
}
# 每次调用都判 body.code，失败时连**原始 body** 一起报（DEV-ENV §6.56）
function Api([string]$method, [string]$url, $body, [string]$what) {
    $hdr = @{ Authorization = "Bearer $(TokenOf 'superAdmin')" }
    try {
        if ($null -eq $body) {
            $r = Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $hdr -TimeoutSec 60
        } else {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 30 -Compress))
            $r = Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $hdr `
                -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 60
        }
    } catch {
        throw "$what 调用异常（{0} {1}）：{2}" -f $method, $url, $_.Exception.Message
    }
    $script:LastBody = ''
    try { $script:LastBody = ($r | ConvertTo-Json -Depth 30 -Compress) } catch { $script:LastBody = "$r" }
    Say ("    {0} {1} -> code={2}" -f $method, $url, $r.code)
    return $r
}
function ExpectOk($r, [string]$what) {
    if ($null -eq $r -or $r.code -ne 200) {
        Bad ("$what 未成功：code={0}；原始 body={1}" -f $r.code, $script:LastBody)
    }
    return ($null -ne $r -and $r.code -eq 200)
}

function FormContentJson([bool]$field101Required) {
    $req = if ($field101Required) { 'true' } else { 'false' }
    return '{"formRef":"elForm","formModel":"formData","fields":[' +
           '{"__config__":{"label":"合同类型","tag":"el-radio-group","required":' + $req + '},"__vModel__":"field101"},' +
           '{"__config__":{"label":"其他会审部门","tag":"el-checkbox-group","required":true},"__vModel__":"jointDepts"}' +
           ']}'
}
function FlowContentJson([string]$formId) {
    return ('{"schemaVersion":1,"key":"t16probe","name":"T16一致性探针流程","category":"test","formId":"' + $formId + '",' +
        '"nodes":[{"id":"start","type":"start","name":"发起"},' +
        '{"id":"b1","type":"condition","name":"路由","branches":[' +
        '{"id":"b1_1","name":"分支1","groups":[{"logic":"AND","rows":[{"field":"field101","op":"EQ","value":"经营"}]}],' +
        '"nodes":[{"id":"n2","type":"approve","name":"审批"}]},' +
        '{"id":"b1_d","name":"其他情况","defaultBranch":true,"nodes":[{"id":"n3","type":"approve","name":"兜底"}]}]},' +
        '{"id":"end","type":"end","name":"结束"}]}')
}

$FORM1 = 'T16PROBE-FRM-0001'   # 流程侧引用的表单（旧版本）
$FORM2 = 'T16PROBE-FRM-0002'   # 模板侧引用的表单（故意与流程不一致）
$FKEY  = 'T16PROBE-KEY'
$FLOW1 = 'T16PROBE-FLOW-0001'
$TPL1  = 'T16PROBE-TPL-0001'

'' | Out-File -FilePath $Out -Encoding utf8

# ------------------------------------------------------------------ 前置检查

Step '前置检查'
foreach ($p in @(3306, 6379, 5672, 8080)) {
    if (-not [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)) {
        throw "端口 $p 未监听 —— 请先启动环境（MySQL/Redis/RabbitMQ/后端）"
    }
}
if (-not (Test-Path $MySqlCli)) { throw "找不到 mysql 客户端：$MySqlCli" }
Ok 'MySQL 3306 / Redis 6379 / RabbitMQ 5672 / 后端 8080 均在线'

# ------------------------------------------------------------------ ① 只读巡检（真实数据）

Step '① 只读巡检：真实数据（脚本不写任何东西）'
$base = (Api 'GET' '/workflow/simple-flow/form-consistency' $null '巡检接口').data
Say ("    scannedFlows={0} scannedTemplates={1} issueCount={2} high={3} medium={4} low={5}" -f `
     $base.scannedFlows, $base.scannedTemplates, $base.issueCount, $base.highCount, $base.mediumCount, $base.lowCount)
foreach ($i in $base.issues) { Say ("      - [{0}/{1}] {2}" -f $i.severity, $i.kind, $i.detail) }
$baseHigh = @($base.issues | Where-Object { $_.severity -eq 'high' })
Assert-That ($base.PSObject.Properties.Name -contains 'issues') '巡检接口可用并返回 issues 列表'
Assert-That ($baseHigh.Count -eq 0) "真实数据没有 high 级问题（当前 high=$($baseHigh.Count)）"

if ($OnlyReadOnly) {
    Say ''
    Say '=================================================='
    Say ("通过 {0} 项，失败 {1} 项（只读模式）" -f $script:Pass, $script:Fail)
    if ($script:Fail -gt 0) { $script:Failures | ForEach-Object { Say "  - $_" }; exit 1 }
    exit 0
}

# ------------------------------------------------------------------ ② 探针夹具

Step '② 建探针夹具（t_template_dynamic_form ×2 + t_flow_simple ×1 + t_template ×1；id 前缀 T16PROBE-）'
$sqlFile = Join-Path $CacheDir 'flow-form-consistency-probe.sql'
$sql = @"
DELETE FROM t_template_dynamic_form WHERE id IN ('$FORM1','$FORM2');
DELETE FROM t_flow_simple WHERE id = '$FLOW1';
DELETE FROM t_template WHERE id = '$TPL1';
INSERT INTO t_template_dynamic_form (id, name, content, enable_flag, form_key, del_flag, create_by, create_time)
VALUES ('$FORM1', 'T16探针表单-流程侧', '$(FormContentJson $true)', '1', '$FKEY', '0', 't16-probe', now());
INSERT INTO t_template_dynamic_form (id, name, content, enable_flag, form_key, del_flag, create_by, create_time)
VALUES ('$FORM2', 'T16探针表单-模板侧', '$(FormContentJson $true)', '1', '$FKEY', '0', 't16-probe', now());
INSERT INTO t_flow_simple (id, def_key, name, category, content, schema_version, status, version, del_flag, create_id, create_by, create_time, update_id, update_by, update_time)
VALUES ('$FLOW1', 't16probe', 'T16一致性探针流程', 'test', '$(FlowContentJson $FORM1)', 1, '1', 1, '0', '1', 't16-probe', now(), '1', 't16-probe', now());
INSERT INTO t_template (id, name, type, def_key, form_id, form_key, form_type, form_code, enable_flag, del_flag, flow_mode, simple_flow_id, create_id, create_by, create_time, update_id, update_by, update_time)
VALUES ('$TPL1', 'T16探针模板', 'test', 't16probe', '$FORM2', '$FKEY', '1', 'dynamic', '1', '0', '0', '$FLOW1', '1', 't16-probe', now(), '1', 't16-probe', now());
"@
[System.IO.File]::WriteAllText($sqlFile, $sql, (New-Object System.Text.UTF8Encoding($false)))
# ⚠ 含中文的 SQL **不能**经 PowerShell 管道喂 mysql（DEV-ENV §6.33：会被变 ?）⇒ 用 cmd 的文件重定向
$null = & cmd /c ('"{0}" --host=127.0.0.1 --user=root --default-character-set=utf8mb4 {1} < "{2}"' -f $MySqlCli, $Database, $sqlFile) 2>&1
Assert-That ((Sql1 "SELECT COUNT(*) FROM t_template_dynamic_form WHERE form_key='$FKEY';") -eq '2') '探针：两个表单版本行已建'
Assert-That ((Sql1 "SELECT COUNT(*) FROM t_flow_simple WHERE id='$FLOW1';") -eq '1') '探针：流程行已建（status=1，视为已发布）'
Assert-That ((Sql1 "SELECT COUNT(*) FROM t_template WHERE id='$TPL1';") -eq '1') '探针：模板行已建（form_id 故意指向另一个版本）'
# 中文是否按 utf8mb4 正确落库：**经接口回读**判定 —— 不靠 PowerShell 解 mysql 的 stdout
# （那是 PowerShell 的解码问题，会把正确的中文读成乱码，从而冤枉数据）
$probeForm = (Api 'GET' "/template/dynamic/form/$FORM1" $null '探针表单详情').data
Assert-That ($probeForm.content -match '合同类型') '探针：中文内容按 utf8mb4 正确落库（接口回读命中「合同类型」）'

# ------------------------------------------------------------------ ③ 检出

Step '③ 构造错位 → 巡检接口必须检出，并定位到具体流程与模板'
$r3 = (Api 'GET' '/workflow/simple-flow/form-consistency' $null '巡检接口').data
$mis = @($r3.issues | Where-Object { $_.kind -eq 'MISMATCH' -and $_.flowId -eq $FLOW1 })
$mis | ForEach-Object { Say ("      - [{0}/{1}] {2}" -f $_.severity, $_.kind, $_.detail) }
Assert-That ($mis.Count -eq 1) '检出 1 条 MISMATCH'
if ($mis.Count -eq 1) {
    Assert-That ($mis[0].templateId -eq $TPL1) "MISMATCH 定位到模板 $TPL1"
    Assert-That ($mis[0].defKey -eq 't16probe') 'MISMATCH 定位到流程 def_key'
    Assert-That ($mis[0].flowFormId -eq $FORM1 -and $mis[0].templateFormId -eq $FORM2) '两侧 form id 都被报出'
    Assert-That ($mis[0].severity -eq 'high') '严重度 = high'
}

# ------------------------------------------------------------------ ③b 对齐（模拟历史那种"人工对齐数据"）

Step '③b 把探针模板也指回流程用的那一版（= 当年人工对齐数据的动作）→ 巡检必须转干净'
$null = & cmd /c ('"{0}" --host=127.0.0.1 --user=root --default-character-set=utf8mb4 {1} -e "UPDATE t_template SET form_id=''{2}'' WHERE id=''{3}'';"' -f $MySqlCli, $Database, $FORM1, $TPL1) 2>&1
$r3b = (Api 'GET' '/workflow/simple-flow/form-consistency' $null '巡检接口').data
$left3b = @($r3b.issues | Where-Object { $_.flowId -eq $FLOW1 -or $_.templateId -eq $TPL1 })
$left3b | ForEach-Object { Say ("      - [{0}/{1}] {2}" -f $_.severity, $_.kind, $_.detail) }
Assert-That ($left3b.Count -eq 0) '对齐后探针流程/模板干净（同一接口既不漏报也不误报）'

# ------------------------------------------------------------------ ④ PRD V-8 阻断

Step '④ PRD V-8 反向校验：把条件字段改为非必填 → 必须阻断，且不留半成品'
$rowsBefore = [int](Sql1 "SELECT COUNT(*) FROM t_template_dynamic_form WHERE form_key='$FKEY';")
$blk = Api 'PUT' '/template/dynamic/form' @{
    id = $FORM1; name = 'T16探针表单-流程侧'; formKey = $FKEY; enableFlag = '1'; delFlag = '0'
    content = (FormContentJson $false)
} '表单保存（阻断预期）'
Say ("    msg={0}" -f $blk.msg)
$rowsAfter = [int](Sql1 "SELECT COUNT(*) FROM t_template_dynamic_form WHERE form_key='$FKEY';")
Assert-That ($blk.code -eq 500) '保存被阻断（body code=500，HTTP 仍是 200 —— 见 DEV-ENV §6.56）'
Assert-That ($blk.msg -match 'PRD V-8') '阻断文案点明是 PRD V-8 反向校验'
Assert-That ($blk.msg -match 'T16一致性探针流程' -and $blk.msg -match 'field101') '阻断文案列出了受影响的流程与字段'
Assert-That ($rowsBefore -eq $rowsAfter) "阻断时没有插入新版本行（$rowsBefore → $rowsAfter）"

# ------------------------------------------------------------------ ⑤ 放行 + 告警

Step '⑤ 字段仍必填（只改 label）→ 放行，但必须明确告警"流程会与新版错位"'
$wrn = Api 'PUT' '/template/dynamic/form' @{
    id = $FORM1; name = 'T16探针表单-流程侧'; formKey = $FKEY; enableFlag = '1'; delFlag = '0'
    content = (FormContentJson $true)
} '表单保存（放行预期）'
$warnList = @($wrn.formFlowWarnings)
Say ("    affectedTemplates={0} formFlowWarnings={1}" -f $wrn.affectedTemplates, $warnList.Count)
$warnList | ForEach-Object { Say ("      - 流程 {0}({1})：{2}" -f $_.flowName, $_.defKey, $_.reason) }
Assert-That (ExpectOk $wrn '表单保存') '保存放行（code=200）'
Assert-That ($warnList.Count -ge 1) '放行时仍返回流程告警（不得静默）'
Assert-That ((@($warnList | Where-Object { $_.flowId -eq $FLOW1 })).Count -eq 1) '告警里点名了会与新版错位的流程'
$newFormId = Sql1 "SELECT id FROM t_template_dynamic_form WHERE form_key='$FKEY' AND enable_flag='1' ORDER BY create_time DESC, id DESC LIMIT 1;"
$tplNow = Sql1 "SELECT form_id FROM t_template WHERE id='$TPL1';"
Say ("    新版本表单 id={0}；模板已改指={1}" -f $newFormId, $tplNow)
Assert-That ($newFormId -ne $FORM1) '确实生成了新版本表单行'
Assert-That ($tplNow -eq $newFormId) '模板被改指到新版本（既有 §7.10 语义）'
$mis2 = @((Api 'GET' '/workflow/simple-flow/form-consistency' $null '巡检接口').data.issues |
          Where-Object { $_.kind -eq 'MISMATCH' -and $_.flowId -eq $FLOW1 })
Assert-That ($mis2.Count -eq 1) '放行之后接口把"流程留在旧版本"如实报出（这就是本次缺陷本身）'

# ------------------------------------------------------------------ ⑥ 修复

Step '⑥ 修复：把流程 content.formId 改指到新版本（走应用自身的草稿接口）'
$draft = Api 'POST' '/workflow/simple-flow/draft' @{ id = $FLOW1; content = (FlowContentJson $newFormId) } '流程草稿保存'
Assert-That (ExpectOk $draft '流程草稿保存') '草稿保存成功（等价于在设计器点保存）'
$r6 = (Api 'GET' '/workflow/simple-flow/form-consistency' $null '巡检接口').data
$left = @($r6.issues | Where-Object { $_.flowId -eq $FLOW1 -or $_.templateId -eq $TPL1 })
$left | ForEach-Object { Say ("      - [{0}/{1}] {2}" -f $_.severity, $_.kind, $_.detail) }
Assert-That ($left.Count -eq 0) '修复后探针流程/模板不再有任何一致性问题（前后对照：错位 → 干净）'

Step '⑥b 真实数据（不含探针）的巡检结论'
$real = @($r6.issues | Where-Object { $_.flowId -ne $FLOW1 -and $_.templateId -ne $TPL1 })
$realHigh = @($real | Where-Object { $_.severity -eq 'high' })
Say ("    issueCount(不含探针)={0} high={1}" -f $real.Count, $realHigh.Count)
$real | ForEach-Object { Say ("      - [{0}/{1}] {2}" -f $_.severity, $_.kind, $_.detail) }
Assert-That ($realHigh.Count -eq 0) '真实数据里没有 high 级问题'

# ------------------------------------------------------------------ ⑦ 清理

Step '⑦ 收尾：删除探针夹具（探针流程从未发布 ⇒ 无任何 ACT_* 残留）'
if ($KeepProbeFixtures) {
    Say '    （-KeepProbeFixtures：保留夹具以便人工复核）'
    Assert-That $true '按参数保留夹具'
} else {
    $n1 = Sql1 "SELECT COUNT(*) FROM t_template_dynamic_form WHERE form_key='$FKEY';"
    $null = & cmd /c ('"{0}" --host=127.0.0.1 --user=root --default-character-set=utf8mb4 {1} -e "DELETE FROM t_template_dynamic_form WHERE form_key=''T16PROBE-KEY''; DELETE FROM t_flow_simple WHERE id=''T16PROBE-FLOW-0001''; DELETE FROM t_template WHERE id=''T16PROBE-TPL-0001'';"' -f $MySqlCli, $Database) 2>&1
    Say ("    删除：t_template_dynamic_form {0} 行（form_key={1}）+ t_flow_simple 1 行 + t_template 1 行" -f $n1, $FKEY)
    $leftForms = Sql1 "SELECT COUNT(*) FROM t_template_dynamic_form WHERE form_key='$FKEY';"
    $leftFlow = Sql1 "SELECT COUNT(*) FROM t_flow_simple WHERE id='$FLOW1';"
    $leftTpl = Sql1 "SELECT COUNT(*) FROM t_template WHERE id='$TPL1';"
    Say ("    残留检查：forms={0} flows={1} templates={2}" -f $leftForms, $leftFlow, $leftTpl)
    Assert-That ($leftForms -eq '0' -and $leftFlow -eq '0' -and $leftTpl -eq '0') '探针夹具已清干净'
    $final = (Api 'GET' '/workflow/simple-flow/form-consistency' $null '巡检接口').data
    Say ("    最终巡检：issueCount={0} high={1}（与 ① 的基线一致即未污染真实数据）" -f $final.issueCount, $final.highCount)
    Assert-That ($final.issueCount -eq $base.issueCount) '清理后巡检结果回到基线（真实数据未被污染）'
}

# ------------------------------------------------------------------ 汇总

Say ''
Say '=================================================='
Say ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Say '失败明细：'
    $script:Failures | ForEach-Object { Say "  - $_" }
    exit 1
}
Say '一致性问题可检出、V-8 可阻断可告警、修复后可复检 —— 全部通过'
exit 0
