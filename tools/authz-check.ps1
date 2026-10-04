<#
================================================================================
 authz-check.ps1 —— 打印/签名接口的越权防护验收（PRD 11.3 / AC-35）
--------------------------------------------------------------------------------
 为什么单独一个脚本：
   越权防护**只能绕过前端验证** —— 界面上根本点不出"别人的单据"这个入口，
   而漏洞恰恰在于"换个 businessId / taskId 直接调接口"。
   AC-35 的原文就是两句接口级断言：
     · 无查看权的用户调用打印数据接口 → 403
     · 非本任务办理人调用签名接口     → 403

 覆盖：
   1) 打印数据接口：无关人员 403 / 发起人 200 / 参与者 200 / 超级管理员 200
   2) 打印记录接口：无关人员 403；写留痕接口同样 403
   3) 签名接口：替别人签 403（且库里不留痕）；撤别人的签 403
   4) 正向对照：本人任务签名 200

 用法（在仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1

 前置：后端 8080 在线；.cache\token-<user>.txt 有效（失效先跑 .\tools\oa-login.ps1）

 副作用：造一张夹具单据 AUTHZ-BIZ 与一条待办，收尾删除；会写一条**不可删**的签名记录
         （AC-32 只追加，这是设计如此）。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl    = 'http://localhost:8080',
    [string]$MySqlCli   = 'H:\dsh\OA\.cache\mysql\extract\mysql-8.0.40-winx64\bin\mysql.exe',
    [string]$CacheDir   = 'H:\dsh\ruoyiOA\.cache',
    [string]$Database   = 'rad_oa',
    [string]$SampleImage,
    [string]$TemplateId = '1ADB8299342C4D4FA59EC9F38AB5C768',
    [string]$FlowDefKey = 'testSerial',
    [string]$NodeKey    = 'n1',
    [string]$FixtureBizId = 'AUTHZ-BIZ',
    [string]$Owner      = 'zhangwei',
    [string]$OwnerId    = '58FE8668233B422FB69EE575F5F402A5',
    [string]$Stranger   = 'lina',
    [string]$StrangerId = '192F606172CB4405AFD3FA1976CE4098'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

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

<# 执行 SQL（函数内把 ErrorActionPreference 放宽：触发器/约束报错是预期结果之一） #>
function MySql([string[]]$extra) {
    $ErrorActionPreference = 'Continue'
    return (& $MySqlCli "--host=127.0.0.1" '--user=root' "-D" $Database `
                        '--batch' '--skip-column-names' '--default-character-set=utf8mb4' @extra 2>&1)
}
function Sql([string]$q) {
    $out = MySql @('-e', $q)
    $v = (($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' }) | Select-Object -First 1)
    if ($null -eq $v) { return '' }
    $s = [string]$v
    if ($s -eq 'NULL') { return '' }
    return $s.Trim()
}
<# 带引号的 SQL 走 stdin：PS 5.1 把含双引号的 JSON 拼进命令行时会被截断 #>
function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('authz-' + [guid]::NewGuid().ToString('N') + '.sql')
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
function IsForbidden($res, [string]$keyword) {
    return ($res.code -eq 403) -and ((MsgOf $res) -match $keyword)
}

# ------------------------------------------------------------------ 前置

Step '前置检查'
foreach ($f in @($MySqlCli, $SampleImage)) { if (-not (Test-Path $f)) { throw "缺少文件：$f" } }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw '后端 8080 未监听 —— 先跑 .\start-env.ps1'
}
foreach ($u in @('superAdmin', $Owner, $Stranger)) { $null = TokenOf $u }
Ok "后端在线；用例账号：superAdmin / $Owner / $Stranger"

try {
    # ============================================================== 夹具
    Step '准备夹具（一张属于 zhangwei 的单据 + 一张属于 superAdmin 的单据由签名脚本覆盖）'
    $fixture = @"
REPLACE INTO t_workflow_form (id,title,form_data,template_id,create_id,create_time)
VALUES ('$FixtureBizId','越权验收夹具','{"formData":{},"valData":{"amount":10,"reason":"仅用于权限验收"}}','$TemplateId','$OwnerId',NOW());
"@
    $out = SqlFile $fixture
    if ($out -match 'ERROR') { Info "夹具写入输出：$out" }
    Assert-That ((Sql "SELECT create_id FROM t_workflow_form WHERE id='$FixtureBizId'") -eq $OwnerId) `
        "夹具单据已就位，发起人=$Owner（非管理员，用来排除"admin 放行"的干扰）"

    Step '挑选一个属于 superAdmin 的在办任务（用于签名归属断言）'
    $row = Sql "SELECT CONCAT(t.ID_,'|',t.PROC_INST_ID_) FROM ACT_RU_TASK t WHERE t.TASK_DEF_KEY_='$NodeKey' AND t.ASSIGNEE_='superAdmin' AND t.PROC_DEF_ID_ LIKE '${FlowDefKey}:%' ORDER BY t.ID_ DESC LIMIT 1"
    if (-not $row) { throw "找不到 $FlowDefKey/$NodeKey 且办理人为 superAdmin 的在办任务" }
    $c = $row -split '\|'
    $taskId = $c[0]
    Info "taskId=$taskId"

    # ============================================================== AC-35 打印数据
    Step 'AC-35 打印数据接口：无查看权 → 403'
    $denied = Api 'GET' "/workflow/print/data/$FixtureBizId" $null $Stranger
    Assert-That (IsForbidden $denied '查看权') "无关人员取打印数据被拒 403（code=$($denied.code) msg=$(MsgOf $denied)）"

    $asOwner = Api 'GET' "/workflow/print/data/$FixtureBizId" $null $Owner
    Assert-That ($asOwner.code -eq 200) "$Owner 是发起人 → 取打印数据 200（非管理员走通"发起人"这条判定）"

    $asAdmin = Api 'GET' "/workflow/print/data/$FixtureBizId" $null
    Assert-That ($asAdmin.code -eq 200) 'superAdmin → 200（管理员跨单据可见，用于运维与代打）'

    Step 'AC-35 打印记录接口：同样按查看权收口'
    $logDenied = Api 'GET' "/workflow/print/log/list?businessId=$FixtureBizId" $null $Stranger
    Assert-That (IsForbidden $logDenied '查看权') "无关人员查打印记录被拒 403（msg=$(MsgOf $logDenied)）"
    $logWrite = Api 'POST' '/workflow/print/log' @{ businessId = $FixtureBizId; templateId = $TemplateId; pageCount = 1 } $Stranger
    Assert-That (IsForbidden $logWrite '查看权') "无关人员写打印留痕被拒 403（msg=$(MsgOf $logWrite)）"

    Step 'AC-35 参与者路径：待办里出现即视为有查看权'
    $null = SqlFile "REPLACE INTO t_workflow_todo (id,business_id,cur_handler,type,handle_type,del_flag,create_time) VALUES ('AUTHZ-TODO-1','$FixtureBizId','$StrangerId','1','1','0',NOW());"
    $asPart = Api 'GET' "/workflow/print/data/$FixtureBizId" $null $Stranger
    Assert-That ($asPart.code -eq 200) "$Stranger 出现在待办里 → 取打印数据 200（参与者判定生效）"
    $null = SqlFile "DELETE FROM t_workflow_todo WHERE id='AUTHZ-TODO-1';"
    $afterRemove = Api 'GET' "/workflow/print/data/$FixtureBizId" $null $Stranger
    Assert-That (IsForbidden $afterRemove '查看权') '待办被删掉后再次调用又变回 403（权限跟着业务数据走，不是一次性缓存）'

    # ============================================================== AC-35 签名
    Step 'AC-35 签名接口：非本任务办理人 → 403'
    $up = & curl.exe -s -H "Authorization: Bearer $(TokenOf 'superAdmin')" `
                     -F "file=@$SampleImage" "$BaseUrl/common/upload" | ConvertFrom-Json
    $fileId = if ($up.fileName) { $up.fileName } else { $up.url }
    Assert-That ([bool]$fileId) "签名图片上传成功（fileId=$fileId）"
    if (-not $fileId) { throw '上传失败，后续用例无法继续' }

    $stealSign = Api 'POST' '/workflow/sign/record' @{
        businessId = $FixtureBizId; taskId = $taskId; taskDefKey = $NodeKey
        nodeName = '越权验收'; signType = '1'; fileId = $fileId
    } $Owner
    Assert-That (IsForbidden $stealSign '不属于你') "替别人签名被拒 403（code=$($stealSign.code) msg=$(MsgOf $stealSign)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_sign_record WHERE task_id='$taskId' AND sign_user_id='$OwnerId'") -eq '0') `
        '越权请求在库里没有留下任何签名记录'

    $stealRevoke = Api 'POST' "/workflow/sign/record/revoke?businessId=$FixtureBizId&taskId=$taskId&reason=越权尝试" $null $Owner
    Assert-That (IsForbidden $stealRevoke '不属于你') "撤别人的签名同样被拒 403（msg=$(MsgOf $stealRevoke)）"

    Step '正向对照：本人任务签名 200'
    $mySign = Api 'POST' '/workflow/sign/record' @{
        businessId = $FixtureBizId; taskId = $taskId; taskDefKey = $NodeKey
        nodeName = '越权验收（本人）'; signType = '1'; fileId = $fileId
    }
    Assert-That ($mySign.code -eq 200) "办理人本人签名 200（msg=$(MsgOf $mySign)）"
    Assert-That ($mySign.data.signUserId -eq 'superAdmin') '记录里的签名人就是当前登录用户（服务端写入，客户端改不了）'
}
finally {
    Step '收尾：删除夹具'
    $null = SqlFile "DELETE FROM t_workflow_todo WHERE business_id='$FixtureBizId';"
    $null = SqlFile "DELETE FROM t_workflow_form WHERE id='$FixtureBizId';"
    Info "已删除夹具单据与夹具待办：$FixtureBizId"
}

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
