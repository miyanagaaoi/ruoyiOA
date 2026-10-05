<#
================================================================================
 ctms-e2e-check.ps1 —— B3 合同台账「10.1 端到端主链路联调」（任务 10.1）
--------------------------------------------------------------------------------
 为什么要有它（与第 4/5/6 组的分工）：
   第 4~6 组的脚本各自把「合同主体 / 商务要素与质保 / 附件」验到很深，但**没有一个脚本
   把主链路一次串起来**：档案 → 合同（行项+质保+标签）→ 筛选 → 详情 → 变更历史 →
   附件上传/删除 → 软删除 → 30 天内恢复。10.1 要的正是「同一条数据在不同环节之间
   是否自洽」——例如：详情里的标签是不是刚刚提交的那两个、附件变更历史是不是挂在同一份
   合同上、恢复后 deleted_at 是否真的清零。这些**跨环节**的一致性只有串起来才暴露。

 覆盖的十个环节（每条断言都用 SQL 查库核对，不只看接口 200）：
   P1  新增客户档案        → t_ctms_customer 行 + 编码/名称/启用标志
   P2  新增供应商档案      → t_ctms_supplier 行 + 简称非空
   P3  新增合同（行项+质保+标签）
                           → 编号（服务端 17 位）、金额（先舍入再汇总 5.00+0.10=5.10）、
                             标的物摘要（真正落库）、质保到期日、行项 2 条、标签关联
                             （手动的两个 + 类型同名自动标签）
   P4  列表筛选            → 关键字 / 类型+状态+日期区间 / 标签交集（逗号形态）/ 含停用开关
   P5  详情                → 行项/标签/变更历史入口/只读关联单据，且打开前后**逐字段一致**
   P6  变更历史            → 改名留痕 manual（含操作人与旧值）+ 存在 auto 记录
   P7  附件上传/删除       → 元数据落库 + **磁盘文件真实存在** + 下载字节数一致 +
                             删除是**逻辑删除**（del_flag=1）+ 一条 field_name='附件' 的留痕
   P8  软删除              → del_flag/deleted_at/deleted_reason + 默认列表排除 / includeDeleted=1 出现
   P9  30 天内恢复         → del_flag=0 且 deleted_at / deleted_reason **清零**
   P10 30 天边界（整数日差）→ 第 30 天可恢复、第 31 天拒绝（两侧都留 SQL 日差证据）

 用法（在仓库根目录，先跑 start-env.ps1 与 oa-login.ps1）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-e2e-check.ps1

 前置：后端 8080 在线（jar 需含第 5/6/7 组端点）；`.cache\token-superAdmin.txt` 有效；
       rad_oa 已执行 B3 的建表/字典/菜单 SQL。

 副作用：夹具全部用 ASCII 前缀 E2E*，收尾按白名单物理删除（幂等，残留 0）；
         会往 uploadPath 写一个 E2E* 附件并在收尾删除；
         「第 30/31 天」靠直接改 deleted_at 制造，不改系统时间。
         文件头经验 1：**不可并发**（与其它借用 common 角色的脚本也不可并发）——已内置锁文件。
         文件头经验 2：URL 里拼变量后紧跟 ? / 中文必须用 ${var} 或先存变量（DEV-ENV §6.22）；
                      ⚠ 且 **curl.exe 不编码 URL 里的中文**（裸中文 → Tomcat 400 + HTML 错误页），
                      走 curl 的 URL 必须先 [uri]::EscapeDataString（Invoke-RestMethod 会自动编码，
                      所以"列表用中文能过、导出却 400"不是后端坏了）。
         文件头经验 3：跨环节断言一律**按 id**（"结果里有它"没有区分力）。
         文件头经验 4：**主键是服务端 UUID 的表，不能按 id 前缀认自己的夹具** ——
                      t_ctms_customer / t_ctms_supplier 的身份在 `code`（`id LIKE 'E2EC%'` 永远 0 行：
                      清理静默失效 + 残留断言假绿，本轮就是这么漏了 3 个客户/3 个供应商）。
                      收尾除逐表残留外，还必须做一次**全表基线漂移**断言（清理判据的兜底网）。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl    = 'http://localhost:8080',
    [string]$MySqlCli   = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir   = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database   = 'rad_oa',
    [string]$UploadRoot = 'F:\dsh\ruoyiOA\uploadPath',
    [string]$SubjectCode = 'ZC',
    [string]$SignDate    = '2026-09-15',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)   # 管道喂 SQL 给 mysql.exe 时必须（DEV-ENV §6.33）

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

# ---------------------------------------------------------------- SQL 辅助

function MySql([string[]]$extra) {
    $ErrorActionPreference = 'Continue'
    return (& $MySqlCli "--host=127.0.0.1" '--user=root' '-D' $Database `
                        '--batch' '--skip-column-names' '--default-character-set=utf8mb4' @extra 2>&1)
}
function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('ctmse2e-' + [guid]::NewGuid().ToString('N') + '.sql')
    [System.IO.File]::WriteAllText($tmp, $sql, (New-Object System.Text.UTF8Encoding($false)))
    $out = (Get-Content $tmp -Raw -Encoding UTF8) | & $MySqlCli "--host=127.0.0.1" '--user=root' `
        '-D' $Database '--default-character-set=utf8mb4' 2>&1
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    return ($out -join "`n")
}
function Sql([string]$q) {
    $out = MySql @('-e', $q)
    $lines = @($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
    $err = @($lines | Where-Object { $_ -match '^ERROR' })
    if ($err.Count -gt 0) { return ('ERROR: ' + $err[0]) }
    if ($lines.Count -eq 0) { return '' }
    $s = [string]$lines[0]
    if ($s -eq 'NULL') { return '' }
    return $s.Trim()
}
function SqlRows([string]$q) {
    $out = MySql @('-e', $q)
    return @($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' } | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
}

# ---------------------------------------------------------------- HTTP / curl 辅助

function TokenOf([string]$user) {
    $f = Join-Path $CacheDir "token-$user.txt"
    if (-not (Test-Path $f)) { throw "缺少 token 文件：$f（先跑 .\tools\oa-login.ps1）" }
    return (Get-Content $f -Raw).Trim()
}
function Api([string]$method, [string]$url, $body, [string]$user = 'superAdmin') {
    $headers = @{ Authorization = "Bearer $(TokenOf $user)" }
    try {
        if ($method -eq 'GET') { return Invoke-RestMethod "$BaseUrl$url" -Method Get -Headers $headers -TimeoutSec 60 }
        if ($null -eq $body)  { return Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $headers -TimeoutSec 60 }
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
function IsOk($res)  { return ($null -ne $res) -and ($res.code -eq 200) }
function RowsOf($res) { if ($res -and $res.rows) { return @($res.rows) } return @() }
function IdsIn($res, [string]$id) { return @(RowsOf $res | Where-Object { $_.id -eq $id }).Count }

<# 附件上传/下载必须用 curl.exe：上传是 multipart；下载成功时响应体是二进制 #>
function Upload([string]$objectType, [string]$objectId, [string]$filePath, [string]$user = 'superAdmin') {
    $tmp = Join-Path $env:TEMP ('ctmse2e-up-' + [guid]::NewGuid().ToString('N') + '.json')
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
function Download([string]$id, [string]$outFile, [string]$user = 'superAdmin') {
    $out = & curl.exe -s -o $outFile -w '%{http_code}' -H "Authorization: Bearer $(TokenOf $user)" `
        "$BaseUrl/ctms/attachment/$id/download" 2>&1
    $code = [string](@($out) | Select-Object -Last 1)
    $len = 0
    if (Test-Path $outFile) { $len = (Get-Item $outFile).Length }
    return [pscustomobject]@{ Http = $code; Length = $len }
}
<# 导出（第 5 组已把 /export 做成真 Excel）：只判"是不是真 xlsx 字节 + 响应形态"，#>
<#
  ⚠ 教训（本轮实测踩到）：curl.exe **不会**自动编码 URL 里的中文 —— 裸中文查询参数会被
     Tomcat 直接拒绝（HTTP 400 + `<!doctype html>` 错误页）。同一句用 Invoke-RestMethod 却是
     通的（.NET 会自动把 IRI 转成转义 URI），所以"列表筛选用中文能过、导出却 400"不是后端坏了。
     涉及中文的 URL 一律先 `[uri]::EscapeDataString(...)`（调用方负责），并在断言里带上 HTTP 码与 magic，
     让"404/400 错误页"无法伪装成"导出成功"。
#>
function ExportRaw([string]$query, [string]$outFile, [string]$user = 'superAdmin') {
    $out = & curl.exe -s -o $outFile -w '%{http_code}|%{content_type}' -H "Authorization: Bearer $(TokenOf $user)" `
        "$BaseUrl/ctms/contract/export?$query" 2>&1
    $parts = ([string](@($out) | Select-Object -Last 1)) -split '\|'
    $code = $parts[0]
    $ctype = if ($parts.Count -gt 1) { $parts[1] } else { '' }
    $magic = ''
    $hasJsonBody = $false
    if (Test-Path $outFile) {
        $b = [System.IO.File]::ReadAllBytes($outFile)
        if ($b.Length -ge 2) { $magic = [System.Text.Encoding]::ASCII.GetString($b[0..1]) }
        # 真 xlsx 是 ZIP 二进制；若体内出现 JSON 形态的 "rows"/"code" 就说明退回了 JSON 分支
        $head = [System.Text.Encoding]::ASCII.GetString($b[0..([Math]::Min(2047, $b.Length - 1))])
        if ($head -match '"rows"|"code"\s*:') { $hasJsonBody = $true }
    }
    return [pscustomobject]@{ Http = $code; CType = $ctype; Magic = $magic; HasJsonBody = $hasJsonBody; TempFile = $outFile }
}

# ================================================================ 夹具标识（全部 ASCII 前缀）

$S = (Get-Date -Format 'HHmmss') + (Get-Random -Minimum 100 -Maximum 999)
$CustCode = "E2EC$S"
$SupCode  = "E2ES$S"
$TagAName = "E2E标签A$S"
$TagBName = "E2E标签B$S"
$TypeCode = "E2ET$S"
$UomCode  = "E2EU$S"
$ProdCode = "E2EP$S"
$CName    = "E2E1合同$S"
$CNameRe  = "E2E1合同改名$S"

# 中文查询参数**预编码**（理由见文件头经验 2：嵌套引号会提前结束外层字符串）
$encSigned = [uri]::EscapeDataString('已签订')
$encPaying = [uri]::EscapeDataString('付款中')

# ---------------------------------------------------------------- 夹具清理（FK 顺序铁律）

<#
 清理顺序（DEV-ENV §6.45）：关联表 → 变更历史 → 附件 → 行项 → 合同 → 物料域 → 档案 → 标签。
 理由：t_ctms_contract_tag / t_ctms_change_log / t_ctms_attachment / t_ctms_contract_item
       都通过外键引用 t_ctms_contract；行项还引用 t_ctms_product。顺序错了会 1451 中止整脚本。
 判据覆盖两种来源：本轮 name LIKE 'E2E1合同%'，以及**上一轮异常退出**留下的同类残留（同前缀）。
 只动 E2E* 前缀，绝不碰库里的他人夹具（如迁移验收夹具 CTMMIG*）。
#>
function Clear-Fixtures {
    $out = SqlFile @"
-- ⚠ 判据教训（本轮踩到，见文件头经验 4）：t_ctms_customer / t_ctms_supplier 的**主键是服务端 UUID**，
--   业务身份在 `code`；一行 `id LIKE 'E2EC%'` 是**永远匹配不到**的空操作 —— 清理会静默不生效、
--   残留断言还会假绿。所以：合同按 name，档案按 **code**，物料域按自插 id，标签按 name。
DELETE FROM t_ctms_contract_tag WHERE contract_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%' OR customer_id IN (SELECT id FROM t_ctms_customer WHERE code LIKE 'E2EC%') OR supplier_id IN (SELECT id FROM t_ctms_supplier WHERE code LIKE 'E2ES%'));
DELETE FROM t_ctms_change_log WHERE object_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%' OR customer_id IN (SELECT id FROM t_ctms_customer WHERE code LIKE 'E2EC%') OR supplier_id IN (SELECT id FROM t_ctms_supplier WHERE code LIKE 'E2ES%'));
-- 附件删除留痕的 object_type 是 'contract'（= 附件的对象类型）、object_id 是合同 id，上面那条已覆盖；
-- 这里再按"对象类型 + 对象 id"兜一次，防止合同改名/删除后记录漏网。
DELETE FROM t_ctms_change_log WHERE object_type='contract' AND object_id IN (SELECT id FROM t_ctms_attachment WHERE object_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%' OR customer_id IN (SELECT id FROM t_ctms_customer WHERE code LIKE 'E2EC%') OR supplier_id IN (SELECT id FROM t_ctms_supplier WHERE code LIKE 'E2ES%')));
DELETE FROM t_ctms_attachment WHERE object_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%' OR customer_id IN (SELECT id FROM t_ctms_customer WHERE code LIKE 'E2EC%') OR supplier_id IN (SELECT id FROM t_ctms_supplier WHERE code LIKE 'E2ES%'));
DELETE FROM t_ctms_contract_item WHERE contract_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%' OR customer_id IN (SELECT id FROM t_ctms_customer WHERE code LIKE 'E2EC%') OR supplier_id IN (SELECT id FROM t_ctms_supplier WHERE code LIKE 'E2ES%'));
DELETE FROM t_ctms_contract WHERE name LIKE 'E2E1合同%' OR customer_id IN (SELECT id FROM t_ctms_customer WHERE code LIKE 'E2EC%') OR supplier_id IN (SELECT id FROM t_ctms_supplier WHERE code LIKE 'E2ES%');
DELETE FROM t_ctms_product WHERE id LIKE 'E2EP%';
DELETE FROM t_ctms_uom WHERE id LIKE 'E2EU%';
DELETE FROM t_ctms_product_type WHERE id LIKE 'E2ET%';
DELETE FROM t_ctms_customer WHERE code LIKE 'E2EC%';
DELETE FROM t_ctms_supplier WHERE code LIKE 'E2ES%';
DELETE FROM t_ctms_tag WHERE name LIKE 'E2E标签%';
"@
    if ($out -match 'ERROR') { Bad "夹具清理出现 SQL 错误（按 FK 名定位依赖顺序）：$out" }
    # 上一轮异常退出可能留下的物理附件（存名以原名开头）也一并清掉
    if (Test-Path $UploadRoot) {
        Get-ChildItem $UploadRoot -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'E2E-att*' } | ForEach-Object { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }
    }
}

# ------------------------------------------------------------------ 运行锁（不可并发）

$script:LockFile = Join-Path $CacheDir 'ctms-e2e-check.lock'
if ((Test-Path $script:LockFile) -and (-not $Force)) {
    $holder = (Get-Content $script:LockFile -Raw -ErrorAction SilentlyContinue)
    throw "检测到运行锁 $($script:LockFile)（$holder）。本脚本不可并发；确认没有其它实例后删除该文件，或用 -Force 跳过。"
}
[System.IO.File]::WriteAllText($script:LockFile, "pid=$PID start=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')", (New-Object System.Text.UTF8Encoding($false)))

# ------------------------------------------------------------------ 前置

Step '前置检查'
if (-not (Test-Path $MySqlCli)) { throw "缺少 MySQL 客户端：$MySqlCli" }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) { throw '后端 8080 未监听 —— 先跑 .\start-env.ps1' }
$null = TokenOf 'superAdmin'
$tables = @('t_ctms_contract','t_ctms_contract_item','t_ctms_contract_tag','t_ctms_change_log','t_ctms_tag','t_ctms_customer','t_ctms_supplier','t_ctms_product','t_ctms_product_type','t_ctms_uom','t_ctms_attachment')
$missing = @()
foreach ($t in $tables) {
    if ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$t'") -ne '1') { $missing += $t }
}
if ($missing.Count -gt 0) { throw "缺少 B3 业务表：$($missing -join ', ')" }
$adminId = Sql "SELECT user_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
$adminDept = Sql "SELECT dept_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
$typeDict = Api 'GET' '/system/dict/data/type/contract_types' $null
Assert-That ((IsOk $typeDict) -and (@($typeDict.data).Count -ge 6)) "合同类型字典可用（$(@($typeDict.data).Count) 项）"
Info "夹具序号 $S；签订日期=$SignDate；主体码=$SubjectCode"
Info "夹具前缀：合同 E2E1合同* / 客户 E2EC* / 供应商 E2ES* / 标签 E2E标签* / 物料域 E2E*"

Clear-Fixtures

<#
  零夹具基线（任务 10.1 的必做前置②）：真库里可能有**别人的**遗留夹具（如第 7 组迁移验收的
  CTMMIG*：23 份合同 / 17 条草案 / 2 个供应商）。本脚本的所有断言都**按自己夹具的 id/code 判定**，
  从不依赖"列表总行数"；同时这里记下清理后的全表基线，收尾再比一次 —— 这样任何"没清干净"
  都会以**基线漂移**的形式暴露（本轮就是这么抓到 id/code 前缀写错的假绿）。
#>
$baseCtm = [int](Sql "SELECT COUNT(*) FROM t_ctms_contract")
$baseCust = [int](Sql "SELECT COUNT(*) FROM t_ctms_customer")
$baseSup = [int](Sql "SELECT COUNT(*) FROM t_ctms_supplier")
$baseAtt = [int](Sql "SELECT COUNT(*) FROM t_ctms_attachment")
Info "零夹具基线：contract=$baseCtm customer=$baseCust supplier=$baseSup attachment=$baseAtt（含他人遗留夹具，本脚本只按自身 id/code 判定）"

$attFile = ''
$upFile  = Join-Path $env:TEMP "E2E-att-$S.pdf"
$dlFile  = Join-Path $env:TEMP "E2E-att-download-$S.bin"
$xlFile  = Join-Path $env:TEMP "E2E-export-$S.xlsx"

try {
    # ============================================================ P1 新增客户档案
    Step 'P1 新增客户档案（落库核对）'
    $r = Api 'POST' '/ctms/partner/customer' @{ code = $CustCode; name = "E2E客户$S"; shortName = ''; creditLimit = 12345.67; level = 'A'; enableFlag = '1' }
    Assert-That (IsOk $r) "新增客户返回 200（msg=$(MsgOf $r)）"
    $custId = Sql "SELECT id FROM t_ctms_customer WHERE code='$CustCode'"
    Assert-That ($custId -ne '' -and $custId -notmatch '^ERROR') "客户已落库且 id 由服务端生成（id=$custId）"
    Assert-That ((Sql "SELECT name FROM t_ctms_customer WHERE id='$custId'") -eq "E2E客户$S") '客户名称落库一致'
    Assert-That ((Sql "SELECT enable_flag FROM t_ctms_customer WHERE id='$custId'") -eq '1') '客户启用标志落库为 1'

    # ============================================================ P2 新增供应商档案
    Step 'P2 新增供应商档案（简称非空）'
    $r = Api 'POST' '/ctms/partner/supplier' @{ code = $SupCode; name = "E2E供应商$S"; shortName = "E2E供$S"; supplyScope = '阀门/法兰'; paymentDays = 30; enableFlag = '1' }
    Assert-That (IsOk $r) "新增供应商返回 200（msg=$(MsgOf $r)）"
    $supId = Sql "SELECT id FROM t_ctms_supplier WHERE code='$SupCode'"
    Assert-That ($supId -ne '' -and $supId -notmatch '^ERROR') "供应商已落库且 id 由服务端生成（id=$supId）"
    Assert-That ((Sql "SELECT short_name FROM t_ctms_supplier WHERE id='$supId'") -eq "E2E供$S") '供应商简称落库一致（规格要求非空）'

    # ============================================================ P3 新增合同（行项+质保+标签）
    Step 'P3 新增合同：行项 2 条 + 质保 12 个月 + 标签 A/B'
    # 物料域夹具（行项要引用物料档案）
    $null = SqlFile @"
INSERT INTO t_ctms_product_type (id,name,code,path,level,sort,enable_flag,create_time,update_time) VALUES ('E2ET$S','E2E类型$S','E2ETC$S','/',1,1,'1',NOW(),NOW());
INSERT INTO t_ctms_uom (id,code,name,decimals,enable_flag,create_time,update_time) VALUES ('E2EU$S','E2EUC$S','E2E单位$S',3,'1',NOW(),NOW());
INSERT INTO t_ctms_product (id,code,name,spec,product_type_id,uom_id,default_price,enable_flag,create_id,create_time,update_time)
  VALUES ('E2EP$S','E2EPC$S','E2E球阀$S','DN50','E2ET$S','E2EU$S',12.3456,'1','$adminId',NOW(),NOW());
"@
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_product WHERE id='E2EP$S'") -eq '1') '物料域夹具（类型/单位/物料 DN50）已就位'

    $r = Api 'POST' '/ctms/tag' @{ name = $TagAName; color = '#409eff' }
    Assert-That (IsOk $r) "标签 A 新建成功（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/tag' @{ name = $TagBName; color = '#67c23a' }
    Assert-That (IsOk $r) "标签 B 新建成功（msg=$(MsgOf $r)）"
    $tagA = Sql "SELECT id FROM t_ctms_tag WHERE name='$TagAName'"
    $tagB = Sql "SELECT id FROM t_ctms_tag WHERE name='$TagBName'"
    Assert-That ($tagA -ne '' -and $tagB -ne '' -and $tagA -ne $tagB) '两个标签各自落库且 id 不同'

    $r = Api 'POST' '/ctms/contract' @{
        name = $CName; type = 'SAL'; partyA = "E2E客户$S"; partyB = "E2E供应商$S"
        signDate = $SignDate; effectiveDate = $SignDate; subjectCode = $SubjectCode
        status = '已签订'; arrivalStatus = '未到货'; ownerName = '张伟'
        customerId = $custId; supplierId = $supId; paidAmount = 0
        hasWarranty = '1'; warrantyStart = '2025-06-01'; warrantyMonths = 12
        tagIds = @($tagA, $tagB)
        items = @(
            @{ productId = "E2EP$S"; qty = 3;     unitPrice = 1.665; itemType = '销售' },
            @{ productId = "E2EP$S"; qty = 3.333; unitPrice = 0.03;  itemType = '销售' }
        )
    }
    Assert-That (IsOk $r) "登记合同成功（msg=$(MsgOf $r)）"
    $cid = Sql "SELECT id FROM t_ctms_contract WHERE name='$CName'"
    Assert-That ($cid -ne '' -and $cid -notmatch '^ERROR') "合同已落库（id=$cid）"
    if ($cid -eq '' -or $cid -match '^ERROR') { throw "主干夹具登记失败，后续环节都依赖它：code=$($r.code) msg=$(MsgOf $r)" }

    $no = Sql "SELECT contract_no FROM t_ctms_contract WHERE id='$cid'"
    $noExpected = "^SAL$SubjectCode$($SignDate.Substring(0,4))$($SignDate.Substring(5,2))\d{6}$"
    Assert-That ($no -match $noExpected) "编号由服务端生成且 17 位（类型码+主体码+yyyy+MM+6位序号；实得 $no）"
    Assert-That ((Sql "SELECT create_id FROM t_ctms_contract WHERE id='$cid'") -eq $adminId) 'create_id 由服务端快照（请求体里没传）'
    Assert-That ((Sql "SELECT dept_id FROM t_ctms_contract WHERE id='$cid'") -eq $adminDept) 'dept_id 由服务端快照'
    Assert-That ((Sql "SELECT customer_id FROM t_ctms_contract WHERE id='$cid'") -eq $custId) '客户档案引用落库'
    Assert-That ((Sql "SELECT supplier_id FROM t_ctms_contract WHERE id='$cid'") -eq $supId) '供应商档案引用落库'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_item WHERE contract_id='$cid'") -eq '2') '行项 2 条随主表一起落库（FK 顺序正确）'
    $itemTotals = Sql "SELECT GROUP_CONCAT(total ORDER BY seq) FROM t_ctms_contract_item WHERE contract_id='$cid'"
    Assert-That ($itemTotals -eq '5.00,0.10') "行总价逐行 HALF_UP 到分（实得 $itemTotals）"
    $amount = Sql "SELECT amount FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($amount -eq '5.10') "合同金额 = 已舍入行总价之和（实得 $amount，期望 5.10）"
    $tmp = Sql "SELECT subject_matter FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($tmp -match '球阀' -and $tmp -match 'DN50') "标的物摘要真正落库（$tmp）"
    Assert-That ((Sql "SELECT warranty_end FROM t_ctms_contract WHERE id='$cid'") -eq '2026-05-31') '质保到期日 = 2025-06-01 + 12 个月 → 2026-05-31（服务端算法）'
    Assert-That ((Sql "SELECT warranty_months FROM t_ctms_contract WHERE id='$cid'") -eq '12') '质保月数落库'
    $tagRows = SqlRows "SELECT CONCAT(t.name,'|',ct.auto) FROM t_ctms_contract_tag ct JOIN t_ctms_tag t ON t.id=ct.tag_id WHERE ct.contract_id='$cid' ORDER BY ct.auto, t.name"
    Assert-That (@($tagRows | Where-Object { $_ -eq "$TagAName|0" }).Count -eq 1) '标签 A 以手动（auto=0）关联落库'
    Assert-That (@($tagRows | Where-Object { $_ -eq "$TagBName|0" }).Count -eq 1) '标签 B 以手动（auto=0）关联落库'
    Assert-That (@($tagRows | Where-Object { $_ -eq 'SAL|1' }).Count -eq 1) '类型同名自动标签（SAL, auto=1）自动附加'
    Info "标签关联：$($tagRows -join ' , ')"

    # ============================================================ P4 列表筛选
    Step 'P4 列表筛选：关键字 / 组合条件 / 标签交集 / 含停用'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&keyword=$CName"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '关键字命中本夹具合同'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&type=SAL&status=$encSigned&beginSignDate=2026-01-01&endSignDate=2026-12-31"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '组合筛选（类型+状态+签订日期区间）命中'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&tagIds=${tagA},${tagB}"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '标签**交集**（A+B 逗号形态）命中本夹具'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&tagIds=${tagA}"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '单标签 A 也命中（区分"交集"与"或"）'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&status=$encPaying"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 0)) '状态不匹配时按 id 不命中（条件真的拼进了 SQL）'
    # ⚠ 中文查询参数必须自己编码（curl 不编码 → Tomcat 400，见 ExportRaw 上方注释）
    $encName = [uri]::EscapeDataString($CName)
    $xl = ExportRaw "pageNum=1&pageSize=200&keyword=$encName" $xlFile
    Assert-That ($xl.Http -eq '200' -and $xl.Magic -eq 'PK') "导出接口返回真 Excel 字节（HTTP=$($xl.Http) magic=$($xl.Magic)）"
    Assert-That ($xl.CType -match 'spreadsheetml') "导出 Content-Type 是 xlsx（实得 $($xl.CType)）"
    Assert-That (-not $xl.HasJsonBody) '导出响应体不是 JSON（体内无 "rows"/"code"，即真的走了 ExcelUtil 分支）'

    # ============================================================ P5 详情（只读）
    Step 'P5 详情：行项/标签/变更历史入口/只读关联单据，且打开前后逐字段一致'
    $before = Sql "SELECT CONCAT(amount,'|',status,'|',arrival_status,'|',IFNULL(paid_amount,''),'|',del_flag) FROM t_ctms_contract WHERE id='$cid'"
    $r = Api 'GET' "/ctms/contract/$cid"
    Assert-That (IsOk $r) "详情接口可用（msg=$(MsgOf $r)）"
    Assert-That ($r.data.id -eq $cid) '详情返回同一合同'
    Assert-That (@($r.data.items).Count -eq 2) "详情返回行项 2 条（$(@($r.data.items).Count)）"
    Assert-That (@($r.data.tags).Count -ge 3) "详情返回标签（$(@($r.data.tags).Count) 个：A/B + 自动）"
    Assert-That ($null -ne $r.data.changeLogs) '详情带变更历史入口'
    Assert-That ($null -eq $r.data.relatedDocs -or @($r.data.relatedDocs).Count -eq 0) '只读「关联单据」区块存在（B3 阶段为空列表）'
    $after = Sql "SELECT CONCAT(amount,'|',status,'|',arrival_status,'|',IFNULL(paid_amount,''),'|',del_flag) FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($before -eq $after) "打开详情前后逐字段一致（无回写副作用）：$after"

    # ============================================================ P6 变更历史
    Step 'P6 变更历史：改名留痕 manual（含操作人/旧值）+ 存在 auto 记录'
    $detail = Api 'GET' "/ctms/contract/$cid" $null
    $rename = @{}
    foreach ($p2 in $detail.data.PSObject.Properties) { $rename[$p2.Name] = $p2.Value }
    $rename['name'] = $CNameRe
    $r = Api 'PUT' '/ctms/contract' $rename
    Assert-That (IsOk $r) "编辑改名成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT name FROM t_ctms_contract WHERE id='$cid'") -eq $CNameRe) '改名已落库'
    $r = Api 'GET' "/ctms/contract/$cid/change-logs?pageNum=1&pageSize=200"
    $logs = RowsOf $r
    Assert-That ((IsOk $r) -and ($logs.Count -gt 0)) "变更历史接口可用（total=$($r.total)）"
    $nameLog = @($logs | Where-Object { $_.newValue -eq $CNameRe }) | Select-Object -First 1
    Assert-That ($null -ne $nameLog) '改名产生了变更历史'
    Assert-That ($null -ne $nameLog -and $nameLog.source -eq 'manual') '改名称留痕为 manual 来源'
    Assert-That ($null -ne $nameLog -and $nameLog.oldValue -eq $CName) '留痕带上了旧值'
    Assert-That ($null -ne $nameLog -and $nameLog.operatorId -eq $adminId) '留痕含操作人标识'
    Assert-That (@($logs | Where-Object { $_.source -eq 'auto' }).Count -gt 0) '存在 auto 来源记录（金额/标的物/质保到期自动重算）'

    # ============================================================ P7 附件上传 / 删除
    Step 'P7 附件：上传（元数据+磁盘文件）/ 下载字节数一致 / 删除（逻辑删除 + 留痕）'
    $payload = [System.Text.Encoding]::UTF8.GetBytes(('E2E attachment fixture ' + $S + ' ' + ('x' * 300)))
    [System.IO.File]::WriteAllBytes($upFile, $payload)
    Assert-That ((Test-Path $upFile) -and ((Get-Item $upFile).Length -eq $payload.Length)) "附件夹具文件已就位（$($payload.Length) 字节）"
    $up = Upload 'contract' $cid $upFile
    $attId = if ($up.Json -and $up.Json.data) { [string]$up.Json.data.id } else { '' }
    $storedPath = if ($up.Json -and $up.Json.data) { [string]$up.Json.data.storedPath } else { '' }
    $upSize = if ($up.Json -and $up.Json.data) { [string]$up.Json.data.sizeBytes } else { '' }
    Assert-That ($up.Http -eq '200' -and [bool]$attId) "附件上传成功（HTTP=$($up.Http) id=$attId）"
    Assert-That ((Sql "SELECT CONCAT(object_type,'|',object_id,'|',file_name) FROM t_ctms_attachment WHERE id='$attId'") -eq "contract|$cid|E2E-att-$S.pdf") '附件元数据（对象类型/对象ID/文件名）落库正确'
    Assert-That ($storedPath -like '/profile/upload/*') "落库路径是 / 开头的相对路径（$storedPath）"
    if ($storedPath -like '/profile/*') { $attFile = Join-Path $UploadRoot ($storedPath -replace '^/profile/','') }
    Assert-That ([bool]$attFile -and (Test-Path $attFile)) "磁盘文件真实存在（$attFile）"
    if ($attFile -and (Test-Path $attFile)) {
        Assert-That ((Get-Item $attFile).Length -eq $payload.Length) "磁盘文件字节数 == 上传字节数（$($payload.Length)）"
    } else { Assert-That $false '磁盘文件字节数核对（文件不存在，跳过即失败）' }
    $dl = Download $attId $dlFile
    Assert-That ($dl.Http -eq '200' -and $dl.Length -eq $payload.Length) "下载返回 200 且字节数一致（HTTP=$($dl.Http) 字节=$($dl.Length)）"
    $r = Api 'DELETE' "/ctms/attachment/$attId" $null
    Assert-That (IsOk $r) "删除附件接口返回 200（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE id='$attId' AND del_flag='1'") -eq '1') '删除是**逻辑删除**（行仍在库且 del_flag=1，留痕可审计）'
    $r = Api 'GET' "/ctms/attachment/list?objectType=contract&objectId=$cid" $null
    Assert-That ((IsOk $r) -and (@($r.data).Count -eq 0)) '删除后附件列表不再返回该附件'
    # ⚠ 口径以源码为准：writeDeleteLog 写的是 attachment.getObjectType()/getObjectId()，
    #   即**合同的**对象类型与合同 id（不是 'attachment'）——我第一版按 'attachment' 查得到 0 行，是断言写错了。
    $attLog = Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE object_type='contract' AND object_id='$cid' AND field_name='附件' AND operator_id='$adminId'"
    Assert-That ($attLog -eq '1') "删除写了一条 field_name='附件' 的变更历史且含操作人（object_type=contract/object_id=合同id，count=$attLog）"
    $attOld = Sql "SELECT old_value FROM t_ctms_change_log WHERE object_type='contract' AND object_id='$cid' AND field_name='附件' LIMIT 1"
    Assert-That ($attOld -match 'E2E-att-') "该留痕的旧值是「文件名（大小）」（实得 $attOld）"

    # ============================================================ P8 软删除
    Step 'P8 软删除（停用）：del_flag / deleted_at / deleted_reason + 列表口径'
    $r = Api 'DELETE' "/ctms/contract/$cid`?reason=E2E停用" $null
    Assert-That (IsOk $r) "软删除成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$cid'") -eq '1') '停用标志已置 1'
    Assert-That ((Sql "SELECT deleted_reason FROM t_ctms_contract WHERE id='$cid'") -eq 'E2E停用') '停用原因已落库'
    $dd = Sql "SELECT DATEDIFF(CURDATE(), DATE(deleted_at)) FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($dd -eq '0') "停用时间已落库（整数日差=$dd）"
    $r = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=200'
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 0)) '默认列表排除已停用合同'
    $r = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=200&includeDeleted=1'
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) 'includeDeleted=1 时已停用合同出现'

    # ============================================================ P9 30 天内恢复
    Step 'P9 30 天内恢复：del_flag 回 0 且 deleted_at / deleted_reason 清零'
    $r = Api 'PUT' "/ctms/contract/$cid/restore" $null
    Assert-That (IsOk $r) "恢复成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$cid'") -eq '0') '恢复后 del_flag 回到 0'
    Assert-That ((Sql "SELECT deleted_at IS NULL FROM t_ctms_contract WHERE id='$cid'") -eq '1') '恢复后 deleted_at 清零（IS NULL）'
    Assert-That ((Sql "SELECT deleted_reason IS NULL FROM t_ctms_contract WHERE id='$cid'") -eq '1') '恢复后 deleted_reason 清零（IS NULL）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE id='$cid' AND deleted_at IS NOT NULL") -eq '0') '三者一致：不存在"恢复了但时间戳还在"的中间态'

    # ============================================================ P10 30 天边界（整数日差）
    Step 'P10 30 天边界：第 30 天可恢复 / 第 31 天拒绝（两侧 SQL 日差证据）'
    $null = Api 'DELETE' "/ctms/contract/$cid`?reason=E2E边界-30" $null
    $null = SqlFile "UPDATE t_ctms_contract SET deleted_at = DATE_SUB(CURDATE(), INTERVAL 30 DAY) + INTERVAL 9 HOUR WHERE id='$cid';"
    $dd30 = Sql "SELECT DATEDIFF(CURDATE(), DATE(deleted_at)) FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($dd30 -eq '30') "边界内侧：整数日差 = 30（实得 $dd30）"
    $r = Api 'PUT' "/ctms/contract/$cid/restore" $null
    Assert-That (IsOk $r) "第 30 天可恢复（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$cid'") -eq '0') '第 30 天恢复后 del_flag=0'
    $null = Api 'DELETE' "/ctms/contract/$cid`?reason=E2E边界-31" $null
    $null = SqlFile "UPDATE t_ctms_contract SET deleted_at = DATE_SUB(CURDATE(), INTERVAL 31 DAY) WHERE id='$cid';"
    $dd31 = Sql "SELECT DATEDIFF(CURDATE(), DATE(deleted_at)) FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($dd31 -eq '31') "边界外侧：整数日差 = 31（实得 $dd31）"
    $r = Api 'PUT' "/ctms/contract/$cid/restore" $null
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '30 天') "第 31 天拒绝恢复并给出「已超过 30 天保留期，无法恢复」（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$cid'") -eq '1') '被拒的恢复没有改动停用标志（仍在停用态）'
}
finally {
    Step '收尾：删除全部夹具并核对残留'
    Clear-Fixtures
    if ($attFile -and (Test-Path $attFile)) { Remove-Item $attFile -Force -ErrorAction SilentlyContinue }
    foreach ($f in @($upFile, $dlFile, $xlFile)) { if (Test-Path $f) { Remove-Item $f -Force -ErrorAction SilentlyContinue } }
    $leftCtm  = Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE name LIKE 'E2E1合同%' OR customer_id IN (SELECT id FROM t_ctms_customer WHERE code LIKE 'E2EC%') OR supplier_id IN (SELECT id FROM t_ctms_supplier WHERE code LIKE 'E2ES%')"
    $leftItem = Sql "SELECT COUNT(*) FROM t_ctms_contract_item WHERE contract_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%')"
    $leftLog  = Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE object_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%')"
    $leftAtt  = Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%')"
    $leftTag  = Sql "SELECT COUNT(*) FROM t_ctms_contract_tag WHERE contract_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE 'E2E1合同%')"
    # ⚠ 按 **code** 判档案（主键是 UUID；按 id 前缀判会永远为 0 = 假绿，本轮踩过）
    $leftMas  = Sql "SELECT (SELECT COUNT(*) FROM t_ctms_customer WHERE code LIKE 'E2EC%') + (SELECT COUNT(*) FROM t_ctms_supplier WHERE code LIKE 'E2ES%') + (SELECT COUNT(*) FROM t_ctms_tag WHERE name LIKE 'E2E标签%') + (SELECT COUNT(*) FROM t_ctms_product WHERE id LIKE 'E2EP%') + (SELECT COUNT(*) FROM t_ctms_uom WHERE id LIKE 'E2EU%') + (SELECT COUNT(*) FROM t_ctms_product_type WHERE id LIKE 'E2ET%')"
    $leftFile = 0
    if (Test-Path $UploadRoot) { $leftFile = @(Get-ChildItem $UploadRoot -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'E2E-att*' }).Count }
    Assert-That ($leftCtm -eq '0') "夹具残留：合同 $leftCtm 行（应为 0）"
    Assert-That ($leftItem -eq '0') "夹具残留：行项 $leftItem 行（应为 0）"
    Assert-That ($leftLog -eq '0') "夹具残留：变更历史 $leftLog 行（应为 0）"
    Assert-That ($leftAtt -eq '0') "夹具残留：附件 $leftAtt 行（应为 0）"
    Assert-That ($leftTag -eq '0') "夹具残留：标签关联 $leftTag 行（应为 0）"
    Assert-That ($leftMas -eq '0') "夹具残留：档案/标签/物料域 $leftMas 行（应为 0）"
    Assert-That ($leftFile -eq 0) "夹具残留：物理附件文件 $leftFile 个（应为 0）"
    # 零夹具基线**漂移**断言：这是"清理判据写错/漏表"的最后一道网（不依赖我是否想到了某张表）
    $nowCtm  = [int](Sql "SELECT COUNT(*) FROM t_ctms_contract")
    $nowCust = [int](Sql "SELECT COUNT(*) FROM t_ctms_customer")
    $nowSup  = [int](Sql "SELECT COUNT(*) FROM t_ctms_supplier")
    $nowAtt  = [int](Sql "SELECT COUNT(*) FROM t_ctms_attachment")
    Assert-That ($nowCtm -eq $baseCtm) "零夹具基线未漂移：合同 $baseCtm → $nowCtm（含他人遗留夹具，不得被本脚本动到）"
    Assert-That ($nowCust -eq $baseCust) "零夹具基线未漂移：客户 $baseCust → $nowCust"
    Assert-That ($nowSup -eq $baseSup) "零夹具基线未漂移：供应商 $baseSup → $nowSup"
    Assert-That ($nowAtt -eq $baseAtt) "零夹具基线未漂移：附件 $baseAtt → $nowAtt"
    Remove-Item $script:LockFile -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
