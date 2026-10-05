<#
================================================================================
 b1-binding-check.ps1 —— 2.0 B1 §4「流程 ↔ 模板绑定」回写链路回归（真实环境）
--------------------------------------------------------------------------------
 为什么单独一个脚本：这条链路的失败形态是**静默**的 ——
 界面说"发布成功"，发起时却找不到流程；或者模板编辑一次，打印模板配置就失联。
 所以必须打接口 + 查库两头对：光看接口返回 200 不算通过。

 覆盖（对应 tasks.md §4.1–§4.4）：
   4.1 取/建草稿：GET /workflow/simple-flow/by-template/{id} 首次调用建草稿并回写绑定；
       连续两次调用返回**同一个流程 id**（不重复创建）
   4.2 发布回写：POST /publish（带 templateId）后，模板的 def_key / simple_flow_id /
       flow_mode **三列**都写上了
   4.3 回写落在启用行：模板 id 不变、行数不变（子表关联不失联）
   4.4 发布失败的显式处理：回写失败时整次发布**回滚**（流程版本不增加）
   附带：发布后该模板可**发起一张单据**（写库确认 t_workflow_form 有行）

 用法（在仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\b1-binding-check.ps1

 前置：后端 8080 在线；.cache\token-superAdmin.txt 有效（失效先跑 .\tools\oa-login.ps1）

 副作用：夹具全部用固定前缀（B1BIND*），收尾删除；发布会在 Flowable 留下一条
         测试流程部署（defKey 前缀 tpl_，不影响存量流程）。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl          = 'http://localhost:8080',
    [string]$MySqlCli         = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir         = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database         = 'rad_oa',
    # 克隆源：一个"能发起"的存量模板（形态完整：有 form_id / form_code / type）
    [string]$SourceTemplateId = '1ADB8299342C4D4FA59EC9F38AB5C768',
    [string]$CloneId          = 'B1BINDTPL00000000000000000000A1'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# ⚠ 必须显式设 $OutputEncoding（2026-10-05 修补，DEV-ENV §6.33）：
#   本脚本有 11 处 SQL 里带中文（夹具名/字段值），而 `Get-Content -Raw | & mysql.exe` 这一步的
#   编码由 $OutputEncoding 决定 —— PowerShell 5.1 默认是 **us-ascii**，会把中文写成 `?`，
#   于是 `WHERE name='中文夹具名'` 永远匹配不到行，表现为"夹具已就位"断言失败这类**看不懂的假失败**。
#   实测：不设时 `SELECT HEX('合同台账')` 经 stdin 得到 3F3F3F3F；设成 UTF-8 后得到 E59088E5908CE58FB0E8B4A6。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

# 出问题时给出**具体**的异常（否则只会看到"缺少 token 文件"之类的间接症状）
trap {
    Write-Host ("SCRIPT-EXCEPTION: " + $_.Exception.GetType().Name + " :: " + $_.Exception.Message) -ForegroundColor Magenta
    break
}

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

function Sql([string]$q) {
    $out = & $MySqlCli "--host=127.0.0.1" '--user=root' "-D" $Database '--batch' '--skip-column-names' `
                       '--default-character-set=utf8mb4' -e $q 2>&1
    $v = ($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' } | Select-Object -First 1)
    if ($null -eq $v) { return '' }
    $s = [string]$v
    if ($s -eq 'NULL') { return '' }
    return $s.Trim()
}

<# 带引号的 SQL 走 stdin（PS 5.1 把含双引号的 JSON 拼进命令行会被截断） #>
function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('b1bind-' + [guid]::NewGuid().ToString('N') + '.sql')
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

<# HTTP：业务码非 200 时后端仍回 HTTP 200，统一按响应体判断 #>
function Api([string]$method, [string]$url, $body, [string]$user = 'superAdmin') {
    $headers = @{ Authorization = "Bearer $(TokenOf $user)" }
    try {
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

# ------------------------------------------------------------------ 前置
Step '前置检查'
if (-not (Test-Path $MySqlCli)) { throw "缺少文件：$MySqlCli" }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw '后端 8080 未监听 —— 先跑 .\start-env.ps1'
}
$null = TokenOf 'superAdmin'
Assert-That ((Sql "SELECT COUNT(*) FROM t_template WHERE id='$SourceTemplateId'") -eq '1') `
    "克隆源模板存在（$SourceTemplateId）"

$flowId = ''
try {
    # ============================================================== 夹具：克隆一个完整模板
    Step '夹具：克隆一个形态完整的模板（含 form_id/form_code/type，保证能发起）'
    $cloneSql = @"
DELETE FROM t_template WHERE id='$CloneId';
INSERT INTO t_template (id, name, type, def_key, form_id, form_key, form_type, form_code,
                        main_text_flag, attach_flag, message_notice_flag, enable_flag, del_flag, sort,
                        create_id, create_by, create_time, update_time)
SELECT '$CloneId', 'B1回写链路回归', type, 'b1bind_draft', form_id, form_key, form_type, form_code,
       main_text_flag, attach_flag, message_notice_flag, '1', '0', sort,
       create_id, create_by, NOW(), NOW()
  FROM t_template WHERE id='$SourceTemplateId';
"@
    $out = SqlFile $cloneSql
    if ($out -match 'ERROR') { Info "夹具写入输出：$out" }
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template WHERE id='$CloneId'") -eq '1') '夹具模板已就位（已启用）'
    $rowsBefore = Sql "SELECT COUNT(*) FROM t_template"

    # ============================================================== 4.1 取/建草稿
    Step '4.1 首次进入流程页签：按模板取/建草稿'
    $first = Api 'GET' "/workflow/simple-flow/by-template/$CloneId" $null
    $flowId = if ($first.data) { $first.data.id } else { '' }
    Assert-That ($first.code -eq 200 -and [bool]$flowId) "by-template 返回草稿（code=$($first.code) id=$flowId msg=$(MsgOf $first)）"
    if (-not $flowId) { throw '草稿创建失败，后续用例无法继续' }
    $expectKey = 'tpl_' + $CloneId.Substring(0, 8)
    Assert-That ($first.data.defKey -eq $expectKey -or $first.data.defKey -like "$expectKey`_*") "流程标识由系统派生：$($first.data.defKey)（期望 $expectKey；唯一性后缀 _n 属预期）"
    Assert-That ($first.data.status -eq '0') '新草稿状态为"未发布"（status=0）'
    Assert-That ((Sql "SELECT IFNULL(simple_flow_id,'') FROM t_template WHERE id='$CloneId'") -eq $flowId) `
        '建草稿时已回写 t_template.simple_flow_id'
    Assert-That ((Sql "SELECT flow_mode FROM t_template WHERE id='$CloneId'") -eq '0') '回写 flow_mode=0（简化流程）'

    Step '4.1 再次进入：必须返回同一个流程 id（不重复创建）'
    $second = Api 'GET' "/workflow/simple-flow/by-template/$CloneId" $null
    Assert-That ($second.data.id -eq $flowId) "第二次返回同一个流程 id（$($second.data.id)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_flow_simple WHERE def_key='$expectKey' AND del_flag='0'") -eq '1') `
        '库里只有一条该标识的草稿（没有重复创建）'

    # ============================================================== 4.2 发布 + 三列回写
    Step '4.2 发布：同事务回写 def_key / simple_flow_id / flow_mode'
    $pub = Api 'POST' '/workflow/simple-flow/publish' @{ id = $flowId; remark = 'B1 回写链路回归'; templateId = $CloneId } 'superAdmin'
    Assert-That ($pub.code -eq 200) "发布成功（code=$($pub.code) msg=$(MsgOf $pub)）"
    Assert-That ((Sql "SELECT def_key FROM t_template WHERE id='$CloneId'") -eq $expectKey) `
        "回写 def_key = $expectKey"
    Assert-That ((Sql "SELECT IFNULL(simple_flow_id,'') FROM t_template WHERE id='$CloneId'") -eq $flowId) `
        '回写 simple_flow_id'
    Assert-That ((Sql "SELECT flow_mode FROM t_template WHERE id='$CloneId'") -eq '0') '回写 flow_mode=0'
    Assert-That ((Sql "SELECT status FROM t_flow_simple WHERE id='$flowId'") -eq '1') '流程已置为"已发布"'
    Assert-That ([int](Sql "SELECT version FROM t_flow_simple WHERE id='$flowId'") -ge 1) '流程版本已递增（≥1）'
    # 回写的 def_key 在引擎侧确实有流程定义：证明"绑定指向一条可跑的定义"，不是只写了个字符串
    $publishedKey = Sql "SELECT def_key FROM t_template WHERE id='$CloneId'"
    Assert-That ([int](Sql "SELECT COUNT(*) FROM ACT_RE_PROCDEF WHERE KEY_='$publishedKey'") -ge 1) `
        "Flowable 里存在该流程标识的流程定义（KEY_=$publishedKey）"

    # ============================================================== 4.3 落在启用行
    Step '4.3 回写落在当前启用行（不是新建行、不是废弃行）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template WHERE id='$CloneId'") -eq '1') '模板仍只有 1 行（原地回写）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template") -eq $rowsBefore) "模板总行数未变（$rowsBefore）"
    Assert-That ((Sql "SELECT del_flag FROM t_template WHERE id='$CloneId'") -eq '0') '该行仍是未删除状态'

    # ============================================================== 发起一张单据
    Step '发布后该模板可以发起一张单据'
    $formCfg = '{"formData":{"fields":[]},"valData":{"reason":"B1回写链路回归","amount":1}}'
    $saveRes = Api 'POST' '/biz/form/save' @{
        templateId = $CloneId
        formData   = @{ formData = $formCfg }
    } 'superAdmin'
    Assert-That ($saveRes.code -eq 200) "发起保存成功（code=$($saveRes.code) msg=$(MsgOf $saveRes)）"
    $bizId = [string]$saveRes.data
    Info "businessId=$bizId"
    # 注意：动态表单实现写 t_workflow_form 时**不带 template_id**（那一列是给业务表单用的），
    # 所以按"接口返回的业务ID"核对单据，模板↔单据的关联看 t_workflow_my_draft.template_id。
    Assert-That ([bool]$bizId -and (Sql "SELECT COUNT(*) FROM t_workflow_form WHERE id='$bizId'") -eq '1') `
        '库里已生成该单据（t_workflow_form 按返回的 businessId 可查到）'
    # 「我起草的」是**异步**落库的（BizFormServiceImpl.createMyDraft 走 RabbitMQ 队列），
    # 所以这里轮询等待而不是立刻断言 —— 立刻查必然查不到（踩过）。
    $draftCount = 0
    for ($i = 0; $i -lt 10; $i++) {
        $draftCount = [int](Sql "SELECT COUNT(*) FROM t_workflow_my_draft WHERE template_id='$CloneId'")
        if ($draftCount -ge 1) { break }
        Start-Sleep -Seconds 1
    }
    Assert-That ($draftCount -ge 1) "「我起草的」异步落库后能看到该模板的单据（轮询 ≤10s，实际 $draftCount 行）"

    # ============================================================== 4.4 失败回滚
    Step '4.4 回写失败必须整次回滚（不留静默悬空态）'
    $verBefore = Sql "SELECT version FROM t_flow_simple WHERE id='$flowId'"
    $badPub = Api 'POST' '/workflow/simple-flow/publish' @{ id = $flowId; remark = '回写失败注入'; templateId = 'NOT-EXIST-TEMPLATE-ID' } 'superAdmin'
    Assert-That ($badPub.code -ne 200) "回写目标不存在 → 发布整体失败（code=$($badPub.code) msg=$(MsgOf $badPub)）"
    Assert-That ((Sql "SELECT version FROM t_flow_simple WHERE id='$flowId'") -eq $verBefore) `
        "失败时流程版本未增加（仍为 $verBefore：部署已随事务回滚）"
    Assert-That ((Sql "SELECT def_key FROM t_template WHERE id='$CloneId'") -eq $expectKey) '失败时模板绑定未被改动'
}
finally {
    Step '收尾：删除夹具'
    if ($flowId) { $null = SqlFile "UPDATE t_flow_simple SET del_flag='1' WHERE id='$flowId';" }
    $null = SqlFile @"
DELETE FROM t_workflow_todo WHERE business_id='$bizId' OR business_id IN (SELECT biz_id FROM t_workflow_my_draft WHERE template_id='$CloneId');
DELETE FROM t_workflow_my_draft WHERE biz_id='$bizId' OR template_id='$CloneId';
DELETE FROM t_workflow_form WHERE id='$bizId';
DELETE FROM t_template_submit_scope WHERE template_id='$CloneId';
DELETE FROM t_template_flow_admin WHERE template_id='$CloneId';
DELETE FROM t_template WHERE id='$CloneId';
"@
    Info "已清理夹具模板、单据与流程草稿（Flowable 的测试部署保留，defKey 前缀 tpl_）"
}

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
