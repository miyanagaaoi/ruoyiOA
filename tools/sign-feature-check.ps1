<#
================================================================================
 sign-feature-check.ps1 —— 签名组件（PRD 第 8 章）在**真实环境**上的验收检查
--------------------------------------------------------------------------------
 为什么要它：
   签名链路的几处关键约束**只用界面点不出来**，点不出来就很容易以为"没问题"：
     · 预存签名「默认唯一」是服务端事务保证的 —— 前端只是看看；
     · 「必须签名才能提交」「只读字段改不动」必须**绕过前端**才验证得了；
     · 签名记录「只追加」（数据库触发器）根本没有接口，只能直接对库动手。
   所以本脚本一律从 HTTP / SQL 层打，不看界面。

 覆盖：
   AC-29 预存签名：新增 / 设默认 / 停用（含"停用默认会摘掉默认标记"）/ 删除 / 默认唯一 / 越权拒绝
   AC-30 预存签名可直接落成签名记录（sign_type=1）—— 界面上"一键使用默认签名"的服务端部分
   AC-31 签名记录含 form_data_hash / record_hash（哈希链字段）
   AC-32 UPDATE / DELETE 签名记录被数据库触发器拒绝
   AC-12 节点只读字段：直接调 /biz/form/update 也改不动（含负向对照）
   AC-25 GET /workflow/sign/policy 与设计器配置一致（"允许方式"生效点）
   AC-26 必需签名节点：未签名提交被服务端拒绝

 用法（在仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\sign-feature-check.ps1
   powershell ... -File .\tools\sign-feature-check.ps1 -SkipAc12     # 跳过要造表单夹具的用例

 前置：MySQL(3306) / Redis / 后端(8080) 在线；.cache\token-<user>.txt 有效
       （失效先跑 .\tools\oa-login.ps1）

 关于「业务ID」：本环境里在办流程实例都是**没有 businessKey** 的历史测试单据
   （`ACT_HI_PROCINST.BUSINESS_KEY_` 为 NULL），而签名接口要求 businessId 非空、
   且 form_data_hash 取自 `t_workflow_form`（取不到就按设计写 null）。因此本脚本
   造一张**本次运行独有**的夹具单据（连同表单行），用它承载 AC-30/31/32，
   任务ID 仍取**真实在办任务**，收尾时删掉夹具。

 副作用：会往真实在办单据上写一条签名记录 —— 按 AC-32 设计上不可删，这是有意的。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl    = 'http://localhost:8080',
    [string]$MySqlCli   = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir   = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database   = 'rad_oa',
    [string]$SampleImage,
    [string]$TemplateId = '1ADB8299342C4D4FA59EC9F38AB5C768',
    [string]$FlowDefKey = 'testSerial',
    [string]$NodeKey    = 'n1',
    [string]$FixtureBizId = '',
    [switch]$SkipAc12
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)   # 管道喂 SQL 给 mysql.exe 时必须（PS5.1 默认 ASCII 会把中文列名变 ?）

if (-not $SampleImage) { $SampleImage = Join-Path $CacheDir 'sign-test.png' }

$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

function Step ($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok   ($m) { Write-Host "    [OK] $m" -ForegroundColor Green }
function Bad  ($m) { Write-Host "    [!!] $m" -ForegroundColor Red }
function Info ($m) { Write-Host "    $m" -ForegroundColor DarkGray }

function Assert-That([bool]$cond, [string]$desc) {
    if ($cond) { Ok $desc; $script:Pass++ } else { Bad $desc; $script:Fail++; $script:Failures += $desc }
}

<#
  执行 SQL。
  ⚠ 函数内必须把 ErrorActionPreference 改成 Continue：AC-32 的用例**期望** mysql
    往 stderr 写 "ERROR 1644…"，而脚本全局是 Stop —— 不改的话那句拒绝会变成
    终止性错误，把整个脚本从用例中途打断（实测踩过）。
#>
function MySql([string[]]$extra) {
    $ErrorActionPreference = 'Continue'
    return (& $MySqlCli "--host=127.0.0.1" '--user=root' "-D" $Database `
                        '--batch' '--skip-column-names' '--default-character-set=utf8mb4' @extra 2>&1)
}

<# 取一行的第一列（不存在返回空串）；mysql 把 NULL 打成字符串 "NULL"，这里一并归一为空 #>
function Sql([string]$q) {
    $out = MySql @('-e', $q)
    $v = (($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' }) | Select-Object -First 1)
    if ($null -eq $v) { return '' }
    $s = [string]$v
    if ($s -eq 'NULL') { return '' }
    return $s.Trim()
}

<# 取多行（每行一个字符串） #>
function SqlRows([string]$q) {
    $out = MySql @('-e', $q)
    return @($out | Where-Object { $_ -and $_ -notmatch '^mysql: \[Warning\]' })
}

<# 原样返回 stdout+stderr（用于验证"被拒绝"，不能吞掉报错） #>
function SqlRaw([string]$q) {
    return (MySql @('-e', $q)) -join "`n"
}

<#
  把 SQL 写到临时文件再从 stdin 喂给 mysql。
  为什么不用 -e：夹具里的表单 JSON 带双引号，PowerShell 5.1 拼进命令行时
  引号会被重新解析，MySQL 收到的是被截断的语句（这个坑实测踩过）。
#>
function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('signcheck-' + [guid]::NewGuid().ToString('N') + '.sql')
    [System.IO.File]::WriteAllText($tmp, $sql, (New-Object System.Text.UTF8Encoding($false)))
    $out = (Get-Content $tmp -Raw -Encoding UTF8) | & $MySqlCli "--host=127.0.0.1" '--user=root' `
        "-D" $Database '--default-character-set=utf8mb4' 2>&1
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    return ($out -join "`n")
}

function TokenOf([string]$user) {
    $f = Join-Path $CacheDir "token-$user.txt"
    if (-not (Test-Path $f)) { throw "缺少 token 文件：$f（先跑 .\tools\oa-login.ps1）" }
    return (Get-Content $f -Raw).Trim()
}

<# HTTP 调用：业务码非 200 时后端仍可能回 200，所以统一按响应体判断，别拿异常当失败 #>
function Api([string]$method, [string]$url, $body, [string]$user = 'superAdmin') {
    $headers = @{ Authorization = "Bearer $(TokenOf $user)" }
    try {
        if ($method -eq 'GET') {
            return Invoke-RestMethod "$BaseUrl$url" -Method Get -Headers $headers -TimeoutSec 60
        }
        if ($null -eq $body) {
            return Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $headers -TimeoutSec 60
        }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20 -Compress))
        return Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 60
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $reader.ReadToEnd()
            try { return ($text | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; msg = $text } }
        }
        return [pscustomobject]@{ code = -1; msg = $_.Exception.Message }
    }
}

function MsgOf($res) { if ($res -and $res.msg) { return [string]$res.msg } return '' }
<#
  预存签名列表，**恒定返回数组**。

  ⚠ 必须用逗号 `return ,$rows`：PowerShell 输出集合时会"解包"，
     单元素数组会变成标量对象，调用方拿到的 `$list.Count` 就是空的
     （实测：库里恰好只有 1 枚预存签名时，"用例前已有预存签名 N 枚"打不出来，
       于是 hadAny 被判成 false，默认签名那条断言跟着误报）。
#>
function PresetList() {
    $res = Api 'GET' '/workflow/sign/preset/list' $null
    $rows = @()
    if ($res -and $res.data) { $rows = @($res.data) }
    return ,$rows
}
function PresetDefault() { return (Api 'GET' '/workflow/sign/preset/default' $null).data }
<# 某任务节点的签名策略 #>
function PolicyOf([string]$taskId) { return (Api 'GET' "/workflow/sign/policy?taskId=$taskId" $null).data }

# ------------------------------------------------------------------ 前置

Step '前置检查'
foreach ($f in @($MySqlCli, $SampleImage)) {
    if (-not (Test-Path $f)) { throw "缺少文件：$f" }
}
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw '后端 8080 未监听 —— 先跑 .\start-env.ps1'
}
$null = TokenOf 'superAdmin'
Ok "后端在线；样本签名图 $SampleImage"

$created = @()
$signRecordId = ''

try {
    # 夹具单据ID每次运行都不同：否则历次运行留下的签名记录会累加，
    # "同一条任务两条记录""三条记录都在"这类条数断言就不再可判（实测踩过）
    if ([string]::IsNullOrWhiteSpace($FixtureBizId)) {
        $FixtureBizId = 'SIGNCHECK-' + (Get-Date).ToString('HHmmss')
    }
    # ============================================================== 夹具单据
    Step '准备夹具单据（签名接口要求 businessId 非空）'
    $fixtureSql = @"
REPLACE INTO t_workflow_form (id,title,form_data,template_id,create_id,create_time)
VALUES ('$FixtureBizId','签名验收夹具','{"formData":{},"valData":{"amount":100,"reason":"原始理由"}}','$TemplateId','superAdmin',NOW());
"@
    $out = SqlFile $fixtureSql
    if ($out -match 'ERROR') { Info "夹具写入输出：$out" }
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_form WHERE id='$FixtureBizId'") -eq '1') "夹具单据已就位（$FixtureBizId）"

    # ============================================================== AC-29
    Step 'AC-29 预存签名：新增 / 默认唯一 / 停用 / 删除 / 越权'

    $up = & curl.exe -s -H "Authorization: Bearer $(TokenOf 'superAdmin')" `
                     -F "file=@$SampleImage" "$BaseUrl/common/upload" | ConvertFrom-Json
    $fileId = if ($up.fileName) { $up.fileName } else { $up.url }
    Assert-That ([bool]$fileId) "签名图片上传成功（fileId=$fileId）"
    if (-not $fileId) { throw '上传失败，后续用例无法继续' }

    $before = PresetList
    $hadAny = $before.Count -gt 0
    Info ("用例前已有预存签名 {0} 枚" -f $before.Count)

    $stamp = (Get-Date).ToString('HHmmss')
    $resA = Api 'POST' '/workflow/sign/preset' @{ name = "AC29-A-$stamp"; fileId = $fileId }
    Assert-That ($resA.code -eq 200 -and $resA.data.id) '新增第一枚预存签名成功'
    $idA = $resA.data.id; if ($idA) { $created += $idA }
    if (-not $hadAny) {
        Assert-That ($resA.data.isDefault -eq '1') '第一枚自动成为默认（否则存完还得再点一次才能用）'
    } else {
        Assert-That ($resA.data.isDefault -eq '0') '已有默认时，新增的这一枚不会抢默认'
    }

    $resB = Api 'POST' '/workflow/sign/preset' @{ name = "AC29-B-$stamp"; fileId = $fileId }
    Assert-That ($resB.code -eq 200 -and $resB.data.id) '新增第二枚预存签名成功'
    $idB = $resB.data.id; if ($idB) { $created += $idB }
    Assert-That ($resB.data.isDefault -eq '0') '第二枚的默认标记为否'

    $null = Api 'PUT' "/workflow/sign/preset/default/$idB" $null
    Assert-That ((PresetDefault).id -eq $idB) '设为默认后，默认签名接口返回的就是这一枚'
    $defaultCount = @(PresetList | Where-Object { $_.isDefault -eq '1' }).Count
    Assert-That ($defaultCount -eq 1) "默认签名唯一（当前共 $defaultCount 枚被标为默认）"

    $resDisable = Api 'PUT' '/workflow/sign/preset' @{ id = $idB; status = '0' }
    Assert-That ($resDisable.code -eq 200 -and $resDisable.data.status -eq '0') '停用默认签名成功'
    Assert-That ($null -eq (PresetDefault)) '停用后默认签名取不到（不留"有默认却用不了"的悬空态）'

    $null = Api 'PUT' '/workflow/sign/preset' @{ id = $idB; status = '1' }
    $null = Api 'PUT' "/workflow/sign/preset/default/$idB" $null
    Assert-That ((PresetDefault).id -eq $idB) '重新启用并设为默认成功'

    $other = Api 'PUT' "/workflow/sign/preset/default/$idA" $null 'zhangwei'
    Assert-That (($other.code -ne 200) -and ((MsgOf $other) -match '只能操作自己的')) `
        "越权操作他人预存签名被服务端拒绝（msg=$(MsgOf $other)）"

    $null = Api 'DELETE' "/workflow/sign/preset/$idA" $null
    Assert-That (@(PresetList | Where-Object { $_.id -eq $idA }).Count -eq 0) '删除后不再出现在列表里'
    $created = @($created | Where-Object { $_ -ne $idA })

    # ============================================================== 选单据
    Step '挑选验证用的在办任务'
    # 认准 testSerial 的部署：只有它的节点配了 fieldReadonly / signTypes，
    # 否则会挑到别的测试流程（节点上没有这些配置，断言就失去意义）
    $ownTask = Sql "SELECT CONCAT(t.ID_,'|',t.PROC_INST_ID_) FROM ACT_RU_TASK t WHERE t.TASK_DEF_KEY_='$NodeKey' AND t.ASSIGNEE_='superAdmin' AND t.PROC_DEF_ID_ LIKE '${FlowDefKey}:%' ORDER BY t.ID_ DESC LIMIT 1"
    if (-not $ownTask) { throw "库里没有 $FlowDefKey 流程 $NodeKey 节点、办理人为 superAdmin 的在办任务（先按 doc\测试数据-流程功能验证.md 起一条流程）" }
    $ot = $ownTask -split '\|'
    $taskId = $ot[0]; $procInsId = $ot[1]
    Info "taskId=$taskId procInsId=$procInsId businessId=$FixtureBizId（夹具）"

    # ============================================================== AC-25
    Step 'AC-25 节点签名策略接口（允许方式生效点）'
    $policy = PolicyOf $taskId
    Assert-That ($null -ne $policy -and [bool]$policy.signMode) '策略接口返回 signMode'
    Assert-That (@($policy.signTypes).Count -ge 1) "策略接口返回 signTypes（$(($policy.signTypes) -join ',')）"
    Assert-That ($policy.signTypes -contains 'HANDWRITE') 'signTypes 含 HANDWRITE'
    Assert-That ($policy.signTypes -notcontains 'PRESET') "signTypes 不含 PRESET —— 与设计器里 $FlowDefKey/$NodeKey 只勾了"手写"一致"

    $bogus = PolicyOf 'NOT-EXIST-TASK'
    Assert-That ($bogus.signMode -eq 'OPTIONAL' -and (($bogus.signTypes) -join ',') -eq 'HANDWRITE,PRESET') `
        '任务不存在时返回 PRD 默认值（OPTIONAL + 手写/预存），不报错、不把签名入口关掉'

    # ============================================================== AC-30 服务端 / AC-31
    Step 'AC-30 / AC-31 用预存签名落签名记录（含哈希链字段）'
    $signRes = Api 'POST' '/workflow/sign/record' @{
        businessId = $FixtureBizId
        taskId     = $taskId
        taskDefKey = $NodeKey
        nodeName   = '自动化验收（预存签名）'
        signType   = '1'
        fileId     = $fileId
    }
    Assert-That ($signRes.code -eq 200) "提交预存签名成功（msg=$(MsgOf $signRes)）"
    $rec = $signRes.data
    $signRecordId = if ($rec) { $rec.id } else { '' }
    Assert-That ([bool]$signRecordId) '返回签名记录 id'
    Assert-That ($rec.signType -eq '1') '签名方式记为 1（预存），不是 0（手写）'
    Assert-That ($rec.fileId -eq $fileId) '记录里的 fileId 就是预存签名那张图'
    Assert-That ($rec.signUserId -and $rec.signUserName) '签名人与姓名由服务端写入'
    Assert-That ($rec.signTime -and $rec.signIp) '签名时间与 IP 由服务端写入'
    Assert-That ($rec.recordHash -and $rec.recordHash.Length -eq 64) 'record_hash 为 64 位（SHA-256）'
    Assert-That ($rec.formDataHash -and $rec.formDataHash.Length -eq 64) 'form_data_hash 为 64 位（表单快照）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_sign_record WHERE id='$signRecordId'") -eq '1') '记录确实落库'

    $eff = (Api 'GET' "/workflow/sign/record/effective?businessId=$FixtureBizId" $null).data
    Assert-That ($eff.$taskId -eq $fileId) 'effective 接口里本节点生效签名 = 刚签的这张图'
    $listRes = (Api 'GET' "/workflow/sign/record/list?businessId=$FixtureBizId" $null).data
    Assert-That (@($listRes | Where-Object { $_.id -eq $signRecordId }).Count -eq 1) '记录列表里能查到这条签名'

    # 哈希链：同单据第二条记录的 prev_hash 必须等于第一条的 record_hash
    $sign2 = Api 'POST' '/workflow/sign/record' @{
        businessId = $FixtureBizId
        taskId     = $taskId
        taskDefKey = $NodeKey
        nodeName   = '自动化验收（重签，AC-28）'
        signType   = '1'
        fileId     = $fileId
    }
    Assert-That ($sign2.code -eq 200 -and $sign2.data.prevHash -eq $rec.recordHash) `
        '第二条记录的 prev_hash = 第一条的 record_hash（哈希链首尾相接）'
    # AC-28 重签：旧记录保留，新记录接着哈希链
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_sign_record WHERE business_id='$FixtureBizId' AND task_id='$taskId'") -eq '2') `
        '同一任务有两条签名记录（重签新增，旧记录一动不动）'

    # AC-28 撤销：追加一条 sign_type=9 的记录，原记录不动；撤签后该节点视为未签
    $revoke = Api 'POST' "/workflow/sign/record/revoke?businessId=$FixtureBizId&taskId=$taskId&reason=自动化验收" $null
    Assert-That ($revoke.code -eq 200 -and $revoke.data.signType -eq '9') '撤销签名成功（追加撤销记录，不是删记录）'
    Assert-That ($revoke.data.prevHash -eq $sign2.data.recordHash) '撤销记录的 prev_hash = 上一条的 record_hash（链没断）'
    $effAfter = (Api 'GET' "/workflow/sign/record/effective?businessId=$FixtureBizId" $null).data
    Assert-That ($null -eq $effAfter.$taskId) '撤签后该节点不再算「已签」（最后一条说了算）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_sign_record WHERE business_id='$FixtureBizId' AND task_id='$taskId'") -eq '3') '三条记录都在（只追加，一条没少）'

    # ============================================================== AC-32
    Step 'AC-32 签名记录只追加：数据库层面拒绝 UPDATE / DELETE'
    $upd = SqlRaw "UPDATE t_workflow_sign_record SET sign_ip='1.1.1.1' WHERE id='$signRecordId';"
    Assert-That ($upd -match '45000|只追加') "UPDATE 被触发器拒绝（$(($upd -replace "`r?`n", ' ').Trim())）"
    $del = SqlRaw "DELETE FROM t_workflow_sign_record WHERE id='$signRecordId';"
    Assert-That ($del -match '45000|只追加') "DELETE 被触发器拒绝（$(($del -replace "`r?`n", ' ').Trim())）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_sign_record WHERE id='$signRecordId'") -eq '1') '记录仍在（没被改掉也没被删掉）'

    # ============================================================== AC-26
    Step 'AC-26 必需签名节点：未签名提交被服务端拒绝'
    $reqHit = $null
    foreach ($row in (SqlRows "SELECT CONCAT(t.ID_,'|',t.PROC_INST_ID_,'|',t.TASK_DEF_KEY_) FROM ACT_RU_TASK t WHERE t.ASSIGNEE_='superAdmin' AND t.ID_<>'$taskId' ORDER BY t.ID_ DESC LIMIT 20")) {
        $c = $row -split '\|'
        if ($c.Count -lt 3) { continue }
        $p = PolicyOf $c[0]
        if ($p -and $p.signRequired) { $reqHit = $c; break }
    }
    if ($reqHit) {
        Info "必需签名节点：taskId=$($reqHit[0]) node=$($reqHit[2])"
        $submit = Api 'POST' '/biz/flow/submit' @{
            operateType = '200'
            flowTask = @{
                taskId = $reqHit[0]; businessId = $FixtureBizId; procInsId = $reqHit[1]
                templateId = $TemplateId
                taskDefKey = $reqHit[2]; comment = '自动化验收：未签名提交应被拒绝'
            }
        }
        $submitMsg = MsgOf $submit
        Assert-That (($submit.code -ne 200) -and ($submitMsg -match '签名')) `
            "未签名提交被拒绝且提示明确（code=$($submit.code) msg=$submitMsg）"
        Assert-That ((Sql "SELECT COUNT(*) FROM ACT_RU_TASK WHERE ID_='$($reqHit[0])'") -eq '1') '任务没有被推进（拒绝发生在完成之前）'
    } else {
        Assert-That $false 'AC-26 未验证：找不到 signMode=REQUIRED 的在办任务'
    }

    # ============================================================== AC-12
    if (-not $SkipAc12) {
        Step 'AC-12 节点只读字段：直接调接口也改不动'
        $readonly = Sql "SELECT GROUP_CONCAT(field_vmodel) FROM t_template_node_field_auth WHERE template_id='$TemplateId' AND task_def_key='$NodeKey' AND readonly='1'"
        Info "节点 $NodeKey 的只读字段：$readonly"
        Assert-That ($readonly -match 'amount') '该节点确实配了 amount 为只读'

        $bad = @{
            bizId = $FixtureBizId; templateId = $TemplateId
            formData = @{
                id = $FixtureBizId; title = '签名验收夹具'; taskId = $taskId; templateId = $TemplateId
                procInstId = $procInsId
                formData = '{"formData":{},"valData":{"amount":999,"reason":"原始理由"}}'
            }
        }
        $resBad = Api 'POST' '/biz/form/update' $bad
        Assert-That (($resBad.code -ne 200) -and ((MsgOf $resBad) -match '只读')) `
            "直接改只读字段被拒绝（code=$($resBad.code) msg=$(MsgOf $resBad)）"
        Assert-That ((Sql "SELECT form_data LIKE '%999%' FROM t_workflow_form WHERE id='$FixtureBizId'") -eq '0') `
            '库里没有被写进 999（拒绝发生在写入之前）'

        $good = @{
            bizId = $FixtureBizId; templateId = $TemplateId
            formData = @{
                id = $FixtureBizId; title = '签名验收夹具'; taskId = $taskId; templateId = $TemplateId
                procInstId = $procInsId
                formData = '{"formData":{},"valData":{"amount":100,"reason":"改了理由"}}'
            }
        }
        $resGood = Api 'POST' '/biz/form/update' $good
        Assert-That ((MsgOf $resGood) -notmatch '只读') `
            "负向对照：只改非只读字段不会被判成「只读」（code=$($resGood.code) msg=$(MsgOf $resGood)）"
    } else {
        Info '按要求跳过 AC-12'
    }
}
finally {
    Step '收尾：清理本脚本新建的预存签名与夹具'
    foreach ($id in $created) {
        $r = Api 'DELETE' "/workflow/sign/preset/$id" $null
        Info "删除预存签名 $id -> code=$($r.code)"
    }
    $null = SqlRaw "DELETE FROM t_workflow_form WHERE id='$FixtureBizId';"
    Info "删除夹具单据 $FixtureBizId"
    if ($signRecordId) { Info "签名记录 $signRecordId 按设计保留（AC-32：只追加、不可删）" }
}

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
