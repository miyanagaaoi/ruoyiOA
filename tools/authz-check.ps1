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
   5) 2.0 B1 §3.2/§3.3/§3.4：发起页按可发起范围过滤、越权发起 403 且不落库、
      越权发布 403（流程定义与模板绑定均不变）、被指定流程管理员与超管发布成功
   6) **2.0 B3 §9.3（2026-10-05 追加，兑现第 6 组的欠账）合同台账越权**：
      无权限调用列表 403 / 范围外详情 403（业务码，非 HTTP 层）/ 无查看权下载附件 403 /
      有权限时列表·详情·附件下载 200，以及"同账号同权限、仅数据范围不同"的正反对照。
      ⚠ 数据范围要能造出"范围外"，所以本段会**临时**把受测账号（$Stranger）角色的
      `data_scope` 改成 `'5'`（仅本人创建）并借用 4 个 ctms 菜单行，收尾全部还原；
      入口有 remark 哨兵自愈（DEV-ENV §6.48），不可把 `data_scope='5'` 留在库里。**不碰共享模板**（§9.6）。

 用法（在仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\authz-check.ps1

 前置：后端 8080 在线；.cache\token-<user>.txt 有效（失效先跑 .\tools\oa-login.ps1）

 副作用：造一张夹具单据 AUTHZ-BIZ 与一条待办，收尾删除；会写一条**不可删**的签名记录
         （AC-32 只追加，这是设计如此）。
         2.0 B3 段另造 2 份合同夹具（AUTHZCTM-OTHER-1 / AUTHZCTM-OWN-1）与 1 个真实附件
         （上传到 uploadPath，收尾连库行带物理文件一起删）；
         ⚠ 该段借用 $Stranger 的角色授权/数据范围，**不要与其它会借同一角色的脚本
         （tools\ctms-*.ps1）并发运行**。

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
    [string]$FixtureBizId = 'AUTHZ-BIZ',
    [string]$Owner      = 'zhangwei',
    [string]$OwnerId    = '58FE8668233B422FB69EE575F5F402A5',
    [string]$Stranger   = 'lina',
    [string]$StrangerId = '192F606172CB4405AFD3FA1976CE4098',
    # 2.0 B1：本脚本会新建一条**专用测试流程**并临时绑定到 $TemplateId，收尾时逻辑删除
    [string]$AuthzFlowDefKey = 'authzPublishGuard',
    # 2.0 B1：发布用例专用克隆模板（**不能拿共享模板发布** —— 发布会覆盖其节点只读字段配置）
    [string]$AuthzCloneId = 'AUTHZ-B1-CLONE-TPL-00000000000001',
    # 2.0 B3 §9.3：附件的物理落盘根（与 application.yml 的 profile 一致；收尾删文件用）
    [string]$UploadRoot = 'F:\dsh\ruoyiOA\uploadPath'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)   # 管道喂 SQL 给 mysql.exe 时必须（PS5.1 默认 ASCII 会把中文列名变 ?）

if (-not $SampleImage) { $SampleImage = Join-Path $CacheDir 'sign-test.png' }

$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

<#
  断言计数（任务 9.3 的验收要求"断言数可见地增加"）：
    · 基线 = 30 项（2026-10-05 实测：本脚本在追加 B3 §9.3 之前 `通过 30 项，失败 0 项`，
      **不是**任务原文里写的 14 —— 那是更早版本的口径）；
    · 本次新增 = 15 项（14 条 B3 §9.3 业务/夹具断言 + 1 条末尾的"断言总数自检"）；
    · 脚本末尾会打印总数与差值，并自检 `实际总数 == 基线 + 新增`。
#>
$script:BaselineAssertCount = 30
$script:NewAssertCount = 15

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

# ---------------------------------------------------------------- B3 §9.3 用的补充辅助

<# 权限态切换一律走**完整重新登录**（DEV-ENV §6.47：/getInfo 只在集合变化时才写回缓存，不够可靠） #>
function Relogin([string]$user) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'oa-login.ps1') `
        -Users $user -CacheDir $CacheDir -MySqlCli $MySqlCli -Database $Database 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "重新登录 $user 失败（oa-login.ps1 exit=$LASTEXITCODE）" }
    Info "已对 $user 做**完整重新登录**（oa-login.ps1，非 /getInfo 顶替）"
}
function SqlRows([string]$q) {
    $out = MySql @('-e', $q)
    return @($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' } |
             Where-Object { ([string]$_).Trim() -ne '' } | ForEach-Object { ([string]$_).Trim() })
}
<#
  附件的上传/下载必须用 curl.exe：
    · 上传是 multipart，PS 5.1 的 Invoke-RestMethod 要手搓 boundary；
    · 下载**成功**时响应体是二进制（图片字节），不能走 Invoke-RestMethod 的 JSON 解析；
    · 越权时 HTTP 仍是 200 + JSON（{"code":403,...}），所以两个字段都要看。
#>
function Upload([string]$objectType, [string]$objectId, [string]$filePath, [string]$user = 'superAdmin') {
    $tmp = Join-Path $env:TEMP ('authz-up-' + [guid]::NewGuid().ToString('N') + '.json')
    $out = & curl.exe -s -o $tmp -w '%{http_code}' -X POST "$BaseUrl/ctms/attachment/upload" `
        -H "Authorization: Bearer $(TokenOf $user)" `
        -F "objectType=$objectType" -F "objectId=$objectId" -F "file=@$filePath" 2>&1
    $code = [string](@($out) | Select-Object -Last 1)
    $body = ''
    if (Test-Path $tmp) { $body = (Get-Content $tmp -Raw -Encoding UTF8); Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    $json = $null
    try { $json = $body | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Http = $code; Body = $body; Json = $json }
}
function Download([string]$id, [string]$user = 'superAdmin') {
    $tmp = Join-Path $env:TEMP ('authz-dl-' + [guid]::NewGuid().ToString('N') + '.bin')
    $out = & curl.exe -s -o $tmp -w '%{http_code}' -H "Authorization: Bearer $(TokenOf $user)" `
        "$BaseUrl/ctms/attachment/$id/download" 2>&1
    $code = [string](@($out) | Select-Object -Last 1)
    $len = 0; $body = ''
    if (Test-Path $tmp) {
        $len = (Get-Item $tmp).Length
        if ($len -gt 0 -and $len -lt 4096) { $body = (Get-Content $tmp -Raw -Encoding UTF8) }
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
    $json = $null
    try { $json = $body | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Http = $code; Length = $len; Body = $body; Json = $json }
}
<# 业务码 403 的统一判定：后端越权时 HTTP 仍回 200，一律看响应体 code（DEV-ENV §9.4） #>
function IsBizForbidden($json, [string]$keyword) {
    return ($null -ne $json) -and ($json.code -eq 403) -and (([string]$json.msg) -match $keyword)
}

# ------------------------------------------------------------------ 前置

Step '前置检查'
foreach ($f in @($MySqlCli, $SampleImage)) { if (-not (Test-Path $f)) { throw "缺少文件：$f" } }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw '后端 8080 未监听 —— 先跑 .\start-env.ps1'
}
foreach ($u in @('superAdmin', $Owner, $Stranger)) { $null = TokenOf $u }
Ok "后端在线；用例账号：superAdmin / $Owner / $Stranger"

# ------------------------------------------------------------------
# B3 §9.3：临时借用受测账号（$Stranger）所属角色的授权与数据范围。
# DEV-ENV §6.48 三件套（入口自愈 + 收尾还原 + 哨兵）：
#   · 进入时把本段管理的 4 个 ctms 菜单行删掉 → 才有"无权限 → 403"的干净基线；
#   · 进入时快照原授权 / data_scope / remark，收尾按快照还原
#     （先删管理集合、再插回快照里不属于该集合的部分 —— §6.48 坑①）；
#   · 改 data_scope 前写 sys_role.remark 哨兵，异常退出后下一轮开头能认出来并强制还原。
# ------------------------------------------------------------------
$ctmsMenus    = @('9F2C0000000000000000000000000011', '9F2C0000000000000000000000000021',
                  '9F2C0000000000000000000000000023', '9F2C000000000000000000000000002A')
$ctmsMenuIn   = ($ctmsMenus | ForEach-Object { "'$_'" }) -join ','
$ctmsSentinel = '__B3_AUTHZ_SCOPE_TMP__'
$ctmsOtherId  = 'AUTHZCTMOTHER1'
$ctmsOwnId    = 'AUTHZCTMOWN1'
<# ⚠ 夹具合同 id 必须**纯字母数字**：合同控制器的路径变量正则是 {id:[A-Za-z0-9]+}，
   带连字符的 id 在路由层就匹配不上（现象：详情断言拿到空对象）。附件 id 是服务端 UUID，不含连字符。 #>
$ctmsAttFile  = ''
$strangerRoleId = Sql "SELECT r.role_id FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.role_id WHERE ur.user_id='$StrangerId' LIMIT 1"
$strangerDeptId = Sql "SELECT dept_id FROM sys_user WHERE user_id='$StrangerId'"
$ctmsAdminId    = Sql "SELECT user_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
$ctmsAdminDept  = Sql "SELECT dept_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
if (-not $strangerRoleId -or -not $strangerDeptId -or -not $ctmsAdminId -or -not $ctmsAdminDept) {
    throw "取不到 B3 §9.3 的账号基准：role=$strangerRoleId strangerDept=$strangerDeptId admin=$ctmsAdminId adminDept=$ctmsAdminDept"
}
if ((Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$strangerRoleId'") -eq $ctmsSentinel) {
    $null = SqlFile "UPDATE sys_role SET data_scope='1', remark=NULL WHERE role_id='$strangerRoleId';"
    Info "入口自愈：$Stranger 角色残留哨兵 $ctmsSentinel → data_scope 已还原为基线 '1'"
}
$ctmsScopeBefore  = Sql "SELECT data_scope FROM sys_role WHERE role_id='$strangerRoleId'"
$ctmsRemarkBefore = Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$strangerRoleId'"
$ctmsGrantsBefore = @(SqlRows "SELECT menu_id FROM sys_role_menu WHERE role_id='$strangerRoleId'")
# 进入即摘掉管理集合：这就是"无权限 403"的基线（同时清掉上一轮可能的残留）
$null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$strangerRoleId' AND menu_id IN ($ctmsMenuIn);"

function Restore-CtmsRole {
    try {
        $null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$strangerRoleId' AND menu_id IN ($ctmsMenuIn);"
        $keep = @($ctmsGrantsBefore | Where-Object { $ctmsMenus -notcontains $_ })
        if ($keep.Count -gt 0) {
            $values = ($keep | ForEach-Object { "('$strangerRoleId','$_')" }) -join ','
            $null = SqlFile "INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES $values;"
        }
        $cs = if ($ctmsScopeBefore) { $ctmsScopeBefore } else { '1' }
        $cr = if ($ctmsRemarkBefore -eq '' -or $ctmsRemarkBefore -eq $ctmsSentinel) { 'NULL' } else { "'$ctmsRemarkBefore'" }
        $null = SqlFile "UPDATE sys_role SET data_scope='$cs', remark=$cr WHERE role_id='$strangerRoleId';"
    } catch {
        Bad "收尾还原 $Stranger 的角色授权/数据范围失败（需人工检查 sys_role_menu / sys_role.data_scope）：$_"
    }
}

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

    # ============================================================== 2.0 B1 §7.8 浮窗越权
    Step 'B1 §7.8 关联审批浮窗：查看权之外一律 403'
    <#
      浮窗是全系统最容易变成越权入口的地方 —— 它把"看别人的单据"做成了顺手一点的动作。
      这里用**同一张夹具单据**双向对照：
        · 无查看权的 $Stranger → 403（不是空数据：空数据会让人以为是"没有内容"）
        · 有查看权的 $Owner     → 200
    #>
    $peekStranger = Api 'GET' "/workflow/related-approval/detail?businessId=$FixtureBizId" $null $Stranger
    Assert-That (IsForbidden $peekStranger '查看权') `
        "无查看权的 $Stranger 取浮窗详情被拒 403（code=$($peekStranger.code) msg=$(MsgOf $peekStranger)）"
    $peekOwner = Api 'GET' "/workflow/related-approval/detail?businessId=$FixtureBizId" $null $Owner
    Assert-That ($peekOwner.code -eq 200) "有查看权的 $Owner 取浮窗详情 200（msg=$(MsgOf $peekOwner)）"
    $peekReverse = Api 'GET' "/workflow/related-approval/reverse?businessId=$FixtureBizId" $null $Stranger
    Assert-That (IsForbidden $peekReverse '查看权') `
        "无查看权的 $Stranger 反查关联关系也被拒 403（msg=$(MsgOf $peekReverse)）"

    # ============================================================== 2.0 B1 §3.2/§3.3/§3.4
    Step '夹具：克隆一个专用模板（**不碰共享模板**）+ 新建一条专用测试流程'
    $origScopeType = Sql "SELECT submit_scope_type FROM t_template WHERE id='$TemplateId'"
    $origFlowId    = Sql "SELECT IFNULL(simple_flow_id,'') FROM t_template WHERE id='$TemplateId'"

    $draftBody = @{
        content = (@{
            schemaVersion = 1
            key           = $AuthzFlowDefKey
            name          = 'AUTHZ-越权发布验收'
            category      = 'authz'
            nodes         = @(
                @{ id = 'start'; type = 'start'; name = '开始' },
                @{ id = 'n1'; type = 'approve'; name = '审批'; multiMode = 'SINGLE'; signMode = 'NONE'
                   assignee = @{ source = 'USER'; userIds = @('superAdmin') }; buttons = @('agree') },
                @{ id = 'end'; type = 'end'; name = '结束' }
            )
        } | ConvertTo-Json -Depth 10 -Compress)
    }
    $draft = Api 'POST' '/workflow/simple-flow/draft' $draftBody
    $authzFlowId = if ($draft.data) { $draft.data.id } else { '' }
    Assert-That ([bool]$authzFlowId) "专用测试流程草稿已创建（code=$($draft.code) id=$authzFlowId）"
    if (-not $authzFlowId) { throw '测试流程草稿创建失败，B1 越权用例无法继续' }

    <#
      为什么发布用例必须用**克隆模板**、不能直接用共享的 $TemplateId：
      发布会在同一事务里 `syncNodeFieldAuth(def)` —— **按 template_id 先删后插**该模板的
      「节点只读字段」配置。共享模板（testSerial）的只读配置来自它自己的流程 JSON
      （sign-feature-check / flow-regression 都依赖它）。一旦把一条**没有 fieldReadonly** 的
      测试流程发布到该模板上，就会把那两行配置清空，连带把另外两个门禁脚本搞挂
      （实测踩过：AC-12「该节点确实配了 amount 为只读」直接失败）。
      所以这里克隆一个模板，绑定与发布都只作用于克隆件。
    #>
    $b1Fixture = @"
DELETE FROM t_template WHERE id='$AuthzCloneId';
INSERT INTO t_template (id, name, type, def_key, form_id, form_key, form_type, form_code,
                        main_text_flag, attach_flag, message_notice_flag, enable_flag, del_flag, sort,
                        create_id, create_by, create_time, update_time)
SELECT '$AuthzCloneId', 'AUTHZ-范围与发布验收', type, 'authz_draft', form_id, form_key, form_type, form_code,
       '0', '0', '0', '1', '0', sort, create_id, create_by, NOW(), NOW()
  FROM t_template WHERE id='$TemplateId';
UPDATE t_template SET submit_scope_type='1', include_child_dept='1', simple_flow_id='$authzFlowId' WHERE id='$AuthzCloneId';
DELETE FROM t_template_submit_scope WHERE id='AUTHZ-SCOPE-1';
INSERT INTO t_template_submit_scope (id,template_id,scope_type,target_id,create_id,create_time)
VALUES ('AUTHZ-SCOPE-1','$AuthzCloneId','1','$OwnerId','1',NOW());
DELETE FROM t_template_flow_admin WHERE id='AUTHZ-FADM-1';
INSERT INTO t_template_flow_admin (id,template_id,user_id,create_id,create_time)
VALUES ('AUTHZ-FADM-1','$AuthzCloneId','$OwnerId','1',NOW());
"@
    $fixtureOut = SqlFile $b1Fixture
    if ($fixtureOut -match 'ERROR') { Info "夹具写入输出：$fixtureOut" }
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template WHERE id='$AuthzCloneId'") -eq '1') '夹具：专用克隆模板已就位（已启用）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template_submit_scope WHERE id='AUTHZ-SCOPE-1'") -eq '1') `
        '夹具：模板可发起范围=指定人员 zhangwei'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template_flow_admin WHERE id='AUTHZ-FADM-1'") -eq '1') `
        '夹具：模板流程管理员=zhangwei'

    Step 'B1 §3.2 发起页按范围过滤（服务端，不是前端隐藏）'
    $listOwner = Api 'GET' '/template/template/newStart' $null $Owner
    Assert-That ((($listOwner.data | ConvertTo-Json -Depth 10) -match [regex]::Escape($AuthzCloneId))) `
        "命中范围的 $Owner 在发起页看得到该模板"
    $listStranger = Api 'GET' '/template/template/newStart' $null $Stranger
    Assert-That (-not (($listStranger.data | ConvertTo-Json -Depth 10) -match [regex]::Escape($AuthzCloneId))) `
        "未命中范围的 $Stranger 在发起页看不到该模板"

    Step 'B1 §3.3 越权发起：无权限账号直接 POST 发起接口 → 403 且不落库'
    $beforeForms = Sql "SELECT COUNT(*) FROM t_workflow_form WHERE template_id='$AuthzCloneId'"
    $denyStart = Api 'POST' '/biz/form/save' @{
        templateId = $AuthzCloneId
        formData   = @{ formData = '{}'; valData = @{ amount = 1; reason = '越权发起验收' } }
    } $Stranger
    Assert-That (IsForbidden $denyStart '发起权限') `
        "无发起权限的 $Stranger 直接调发起接口被拒 403（code=$($denyStart.code) msg=$(MsgOf $denyStart)）"
    $afterForms = Sql "SELECT COUNT(*) FROM t_workflow_form WHERE template_id='$AuthzCloneId'"
    Assert-That ($beforeForms -eq $afterForms) `
        "越权发起没有落库（t_workflow_form 行数 $beforeForms → $afterForms）"

    Step 'B1 §3.4 越权发布：拒绝时流程定义与模板绑定都不许变'
    $verBefore  = Sql "SELECT version FROM t_flow_simple WHERE id='$authzFlowId'"
    $bindBefore = Sql "SELECT IFNULL(simple_flow_id,'') FROM t_template WHERE id='$AuthzCloneId'"
    $denyPub = Api 'POST' '/workflow/simple-flow/publish' @{ id = $authzFlowId; remark = '越权尝试' } $Stranger
    Assert-That (IsForbidden $denyPub '流程管理权限') `
        "非流程管理员的 $Stranger 发布被拒 403（code=$($denyPub.code) msg=$(MsgOf $denyPub)）"
    Assert-That ((Sql "SELECT version FROM t_flow_simple WHERE id='$authzFlowId'") -eq $verBefore) `
        "越权发布没有产生任何流程版本（version 仍为 $verBefore）"
    Assert-That ((Sql "SELECT IFNULL(simple_flow_id,'') FROM t_template WHERE id='$AuthzCloneId'") -eq $bindBefore) `
        '越权发布没有改动模板的流程绑定'

    Step 'B1 §3.4 正向对照：被指定的流程管理员与超管都能发布'
    $okPubOwner = Api 'POST' '/workflow/simple-flow/publish' @{ id = $authzFlowId; remark = '流程管理员发布' } $Owner
    Assert-That ($okPubOwner.code -eq 200) `
        "被指定为流程管理员的 $Owner 发布成功（code=$($okPubOwner.code) msg=$(MsgOf $okPubOwner)）"
    $okPubAdmin = Api 'POST' '/workflow/simple-flow/publish' @{ id = $authzFlowId; remark = '管理员发布' }
    Assert-That ($okPubAdmin.code -eq 200) "superAdmin 发布成功（code=$($okPubAdmin.code) msg=$(MsgOf $okPubAdmin)）"

    # ============================================================== 2.0 B3 §9.3 合同台账越权
    Step 'B3 §9.3 合同台账：无权限列表 403 / 范围外详情 403 / 无查看权下载附件 403 / 有权限 200'
    <#
      四个判别面与"为什么这样设计夹具"（任务 9.3；第 6 组的附件越权断言欠账在这里兑现）：
        · "无权限"与"有权限"必须落在**同一账号**上（$Stranger，只有 common 角色、本批之前没有任何 ctms 权限点），
          否则差异可能来自账号而不是权限点；
        · 要造出"范围外"，必须让该账号的数据范围收窄 —— 所以临时把它的角色改成
          data_scope='5'（仅本人创建），并准备两份合同：
            他建（create_id=superAdmin，范围外）  与  本建（create_id=$StrangerId，范围内）；
        · 附件下载按**对象**鉴权，所以**一个**挂在他建合同上的附件就能拿到三种形态：
          无权限 403 / 有权限且对象可见 200 / 有权限但对象范围外 403。
        ⚠ 不碰共享模板（DEV-ENV §9.6：发布用例只打克隆模板）；权限态切换一律完整重新登录（§6.47）。
    #>
    # ---- 夹具
    $ctmsFixtureOut = SqlFile @"
DELETE FROM t_ctms_attachment WHERE object_type='contract' AND object_id IN ('$ctmsOtherId','$ctmsOwnId');
DELETE FROM t_ctms_contract WHERE id IN ('$ctmsOtherId','$ctmsOwnId');
INSERT INTO t_ctms_contract (id,contract_no,name,subject_matter,amount,currency,paid_amount,status,arrival_status,del_flag,dept_id,create_id,create_time,update_time) VALUES
  ('$ctmsOtherId','AUTHZ-CTM-OTHER-1','越权验收：他建合同','',0.00,'CNY',0.00,'已签订','未到货','0','$ctmsAdminDept','$ctmsAdminId',NOW(),NOW()),
  ('$ctmsOwnId','AUTHZ-CTM-OWN-1','越权验收：本建合同','',0.00,'CNY',0.00,'已签订','未到货','0','$strangerDeptId','$StrangerId',NOW(),NOW());
"@
    if ($ctmsFixtureOut -match 'ERROR') { Info "夹具写入输出：$ctmsFixtureOut" }
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE id IN ('$ctmsOtherId','$ctmsOwnId')") -eq '2') `
        "B3 夹具：他建合同（create_id=superAdmin）+ 本建合同（create_id=$Stranger）已就位"
    $ctmsUp = Upload 'contract' $ctmsOtherId $SampleImage
    $ctmsAttId = if ($ctmsUp.Json -and $ctmsUp.Json.data) { [string]$ctmsUp.Json.data.id } else { '' }
    $ctmsAttStored = if ($ctmsUp.Json -and $ctmsUp.Json.data) { [string]$ctmsUp.Json.data.storedPath } else { '' }
    if ($ctmsAttStored -like '/profile/*') { $ctmsAttFile = Join-Path $UploadRoot ($ctmsAttStored -replace '^/profile/','') }
    Assert-That ($ctmsUp.Http -eq '200' -and [bool]$ctmsAttId -and [bool]$ctmsAttFile) `
        "B3 夹具：他建合同上的真实附件已上传（id=$ctmsAttId storedPath=$ctmsAttStored 落盘=$ctmsAttFile）"

    # ---- ① 无权限（进入时已摘掉管理菜单）：列表 / 详情 / 附件下载 → 业务码 403
    Step 'B3 §9.3 -1 无权限：合同列表 / 合同详情 / 附件下载 → 403'
    Relogin $Stranger
    $denyList = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=10' $null $Stranger
    Assert-That (IsForbidden $denyList '没有权限') "无权限调用合同列表 403（code=$($denyList.code) msg=$(MsgOf $denyList)）"
    $denyDetail = Api 'GET' "/ctms/contract/$ctmsOtherId" $null $Stranger
    Assert-That (IsForbidden $denyDetail '没有权限') "无权限按标识直查合同详情 403（code=$($denyDetail.code) msg=$(MsgOf $denyDetail)）"
    $denyDl = Download $ctmsAttId $Stranger
    Assert-That ($denyDl.Http -eq '200' -and (IsBizForbidden $denyDl.Json '没有权限')) `
        "无查看权下载附件 403（HTTP=$($denyDl.Http) body=$($denyDl.Body -replace '\s+',' ')）"

    # ---- ② 有权限（数据范围仍是进入时的档位）：列表 / 详情 / 下载 → 200
    Step 'B3 §9.3 -2 授权 list/query/edit/attachment:list：列表 / 详情 / 下载 → 200'
    $grantValues = ($ctmsMenus | ForEach-Object { "('$strangerRoleId','$_')" }) -join ','
    $null = SqlFile "INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES $grantValues;"
    Relogin $Stranger
    $okList = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=50' $null $Stranger
    $okIds = @()
    if ($okList.code -eq 200 -and $okList.rows) { $okIds = @($okList.rows | ForEach-Object { [string]$_.id }) }
    Assert-That ($okList.code -eq 200 -and ($okIds -contains $ctmsOwnId) -and ($okIds -contains $ctmsOtherId)) `
        "有权限时列表 200 且两份夹具可见（code=$($okList.code) total=$($okList.total)）"
    $okOwn = Api 'GET' "/ctms/contract/$ctmsOwnId" $null $Stranger
    Assert-That ($okOwn.code -eq 200 -and $okOwn.data.id -eq $ctmsOwnId) "有权限时详情 200（本建合同 $ctmsOwnId）"
    $okOther = Api 'GET' "/ctms/contract/$ctmsOtherId" $null $Stranger
    Assert-That ($okOther.code -eq 200) '同一账号在当前档位取他建合同也 200（证明后面的 403 不是权限点造成的）'
    $okDl = Download $ctmsAttId $Stranger
    Assert-That ($okDl.Http -eq '200' -and $okDl.Length -gt 0 -and $null -eq $okDl.Json) `
        "有查看权且对象可见时附件下载 200（HTTP=$($okDl.Http) 字节数=$($okDl.Length)）"

    # ---- ③ 数据范围收窄到 '5'（仅本人创建）：范围外 403 / 范围内 200 的正反对照
    Step 'B3 §9.3 -3 数据范围 data_scope=5（仅本人创建）：范围外 403 / 范围内 200'
    $null = SqlFile "UPDATE sys_role SET data_scope='5', remark='$ctmsSentinel' WHERE role_id='$strangerRoleId';"
    Relogin $Stranger
    $scopeDeny = Api 'GET' "/ctms/contract/$ctmsOtherId" $null $Stranger
    Assert-That (IsForbidden $scopeDeny '无权访问该合同') "范围外详情 403（业务码 code=$($scopeDeny.code) msg=$(MsgOf $scopeDeny)）"
    $scopeAllow = Api 'GET' "/ctms/contract/$ctmsOwnId" $null $Stranger
    Assert-That ($scopeAllow.code -eq 200 -and $scopeAllow.data.id -eq $ctmsOwnId) `
        '同账号同权限、仅数据范围不同 → 本建合同 200（正反对照）'
    $scopeList = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=50' $null $Stranger
    $scopeIds = @()
    if ($scopeList.code -eq 200 -and $scopeList.rows) { $scopeIds = @($scopeList.rows | ForEach-Object { [string]$_.id }) }
    Assert-That ($scopeList.code -eq 200 -and ($scopeIds -contains $ctmsOwnId) -and -not ($scopeIds -contains $ctmsOtherId)) `
        "列表同样按数据范围收口（含本建、不含他建；共 $($scopeIds.Count) 行）"
    $scopeDl = Download $ctmsAttId $Stranger
    Assert-That ($scopeDl.Http -eq '200' -and (IsBizForbidden $scopeDl.Json '无权访问该合同')) `
        "附件按**对象**鉴权：对象在范围外时下载 403（HTTP=$($scopeDl.Http) body=$($scopeDl.Body -replace '\s+',' ')）"
    $scopeEdit = Api 'PUT' '/ctms/contract' @{ id = $ctmsOtherId; name = 'AUTHZ-越权改名' } $Stranger
    Assert-That (IsForbidden $scopeEdit '无权访问该合同') "编辑入口同口径：范围外编辑 403（code=$($scopeEdit.code) msg=$(MsgOf $scopeEdit)）"
}
finally {
    Step '收尾：还原模板字段并删除 B1 夹具'
    <#
      ⚠️ 历史教训（2026-10-04）：早先版本把"发布用例"直接打在共享模板 $TemplateId 上，
      发布回写会把它的 def_key 改成测试流程的 key（authzPublishGuard），
      于是 flow-regression 的 `startFlow` 按 def_key 起流程时起错了定义、用例全挂。
      现在发布用例只用克隆模板；这里只还原共享模板上被**临时**改过的范围字段。
    #>
    if ($origScopeType) {
        $flowRestore = if ($origFlowId) { "'$origFlowId'" } else { 'NULL' }
        $null = SqlFile "UPDATE t_template SET submit_scope_type='$origScopeType', simple_flow_id=$flowRestore WHERE id='$TemplateId';"
        Info "已还原共享模板：submit_scope_type=$origScopeType simple_flow_id=$(if ($origFlowId) { $origFlowId } else { 'NULL' })"
    }
    $null = SqlFile "DELETE FROM t_template_submit_scope WHERE id='AUTHZ-SCOPE-1';"
    $null = SqlFile "DELETE FROM t_template_flow_admin WHERE id='AUTHZ-FADM-1';"
    # 克隆模板必须删干净：留着会被「新启模板」列表带出来（而且它的 def_key 是占位值）
    $null = SqlFile "DELETE FROM t_template_submit_scope WHERE template_id='$AuthzCloneId';"
    $null = SqlFile "DELETE FROM t_template_flow_admin WHERE template_id='$AuthzCloneId';"
    $null = SqlFile "DELETE FROM t_template WHERE id='$AuthzCloneId';"
    Info "已删除 B1 专用克隆模板与它的范围/管理员明细：$AuthzCloneId"
    if ($authzFlowId) {
        $null = SqlFile "UPDATE t_flow_simple SET del_flag='1' WHERE id='$authzFlowId';"
        Info "已逻辑删除专用测试流程：$authzFlowId（其 Flowable 部署保留，defKey=$AuthzFlowDefKey）"
    }
    Step '收尾：删除夹具'
    $null = SqlFile "DELETE FROM t_workflow_todo WHERE business_id='$FixtureBizId';"
    $null = SqlFile "DELETE FROM t_workflow_form WHERE id='$FixtureBizId';"
    Info "已删除夹具单据与夹具待办：$FixtureBizId"
    Step '收尾：还原受测账号的角色授权/数据范围，并删除 B3 §9.3 夹具'
    Restore-CtmsRole
    $null = SqlFile "DELETE FROM t_ctms_attachment WHERE object_type='contract' AND object_id IN ('$ctmsOtherId','$ctmsOwnId');"
    $null = SqlFile "DELETE FROM t_ctms_contract WHERE id IN ('$ctmsOtherId','$ctmsOwnId');"
    if ($ctmsAttFile -and (Test-Path $ctmsAttFile)) {
        Remove-Item $ctmsAttFile -Force -ErrorAction SilentlyContinue
        Info "已删除附件物理文件：$ctmsAttFile"
    }
    $leftAtt  = Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id IN ('$ctmsOtherId','$ctmsOwnId')"
    $leftCtm  = Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE id IN ('$ctmsOtherId','$ctmsOwnId')"
    $leftMenu = Sql "SELECT COUNT(*) FROM sys_role_menu WHERE role_id='$strangerRoleId' AND menu_id IN ($ctmsMenuIn)"
    $scopeNow = Sql "SELECT CONCAT(data_scope,'|',IFNULL(remark,'')) FROM sys_role WHERE role_id='$strangerRoleId'"
    Info "B3 夹具残留：附件 $leftAtt 行 / 合同 $leftCtm 行 / 管理菜单授权 $leftMenu 行（均应为 0）；$Stranger 角色 data_scope=$scopeNow（哨兵应已清）"
}

Write-Host ''
<# 断言总数自检（任务 9.3："断言数在脚本输出中可见地增加"）：实际 = 基线 30 + 新增 15（含本自检） #>
$declaredTotal = $script:BaselineAssertCount + $script:NewAssertCount
$beforeSelfCheck = $script:Pass + $script:Fail
Assert-That ($beforeSelfCheck + 1 -eq $declaredTotal) `
    "断言总数自检：实际应为 $declaredTotal = V1 AC-35 基线 $($script:BaselineAssertCount) + B3 §9.3 新增 $($script:NewAssertCount)（本自检前为 $beforeSelfCheck）"
Write-Host ("通过 {0} 项，失败 {1} 项｜断言总数 {2} 项 = V1 AC-35 基线 {3} 项 + B3 §9.3 新增 {4} 项" -f `
    $script:Pass, $script:Fail, ($script:Pass + $script:Fail), $script:BaselineAssertCount, $script:NewAssertCount)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
