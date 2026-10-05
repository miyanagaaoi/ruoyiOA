<#
================================================================================
 erp-scope-check.ps1 —— B4 §8.2 库存域「数据范围四档」矩阵实测（t10 的验收脚本）
--------------------------------------------------------------------------------
 覆盖：单据（入库单列表/详情）/ 结存（/stk/stock）/ 流水（/stk/ledger）
       × 四档（1 全部 / 3 本部门 / 4 本部门及下级 / 5 本人）
       + 多角色取并集 + 导出与列表同范围同筛选 + 范围外 403。

 为什么必须"四档彼此可判别"（本脚本的设计核心，别改成随便挑几个部门）：
   4 个测试账号（lina/wangqiang/zhangwei/zhaomin）**同属 `common` 角色**，
   基线分属 4 个**平级**部门，父部门都是根 `000AAA111DAE452F9408183D75290000`：
       · `'1'` 全部   → 看得到 4 张单；
       · `'3'` 本部门 → 只看得到"与自己同部门"的那些；
       · `'5'` 本人   → 只看得到自己创建的；
       · `'4'` 本部门及下级 **必须有一个"本部门是别人祖先"的观察者**才与 `'3'` 区分：
         故本脚本**临时**把观察者 zhangwei 移到**根部门** —— 此时 `'4'` 看得到 4 张（根 + 全部下级），
         同一观察者 `'3'` 看得到 **0** 张（没有行的部门就是根部门）⇒ 两档集合不同。
   同理为区分 `'3'` 与 `'5'`：把 zhaomin **临时**移到 lina 所在的财务部 ——
      `'3'`（本部门）看得到 **lina 那张**（同部门、别人创建），`'5'`（本人）看得到 **zhaomin 自己那张**。
      ⚠ 这条同时验证了"**归属部门在创建时快照**"：doc_zhaomin 的 dept_id 仍是创建时的市场部，
      所以它不会因为 zhaomin 后来调岗而被 `'3'`（财务部）看见。

 共享状态纪律（DEV-ENV §6.48 三件套，必读）：
   · **入口自愈**：`sys_role.remark` 写哨兵；若进来时发现哨兵残留（上轮异常退出），先强制还原
     `common` 的 data_scope/data_scope 与 sys_role_menu，再继续；
   · **收尾还原**：按进入时的快照还原 `common.data_scope` / `remark` / `sys_role_menu` 行，
     以及两个被临时调岗账号的 `sys_user.dept_id`；
   · **哨兵**：任何时刻 `data_scope` 不得留库（收尾断言 `='1'` 且 remark 已清）。
   夹具（单位/仓库/类型/物料/单据）× 4 套，ASCII 前缀 `T10S*`，自建自清；
   结存/流水是"只增不改"，收尾只能按前缀 SQL 收 —— 收尾后断言零残留。

 用法（仓库根目录；先 `start-env.ps1` + `oa-login.ps1`）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-scope-check.ps1
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-scope-check.ps1 -KeepFixtures   # 排障

 退出码：0 = 全绿；1 = 有 FAIL；2 = 前置不满足（后端/库/token/基线）

 ⚠ 本文件必须存成 **UTF-8 with BOM**（PS 5.1 对无 BOM 的 .ps1 按 ANSI 读，中文变乱码并破坏引号）。
 ⚠ 认证/权限失败是 **HTTP 200 + body.code=401/403**（DEV-ENV §6.56），一律按 body.code 判定。

 @author 二开（t10 / §8.2）
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl   = 'http://localhost:8080',
    [string]$MySqlCli  = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir  = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database  = 'rad_oa',
    [string]$Admin     = 'superAdmin',
    [string]$SentinelRole = 'common',
    [switch]$KeepFixtures
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)   # 管道喂 SQL 给 mysql.exe 时必须

$script:Pass = 0
$script:Fail = 0
$script:Failures = @()
$script:Sentinel = 'T10-SCOPE-SENTINEL'

function Step ($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok   ($m) { Write-Host "    [OK] $m" -ForegroundColor Green }
function Bad  ($m) { Write-Host "    [!!] $m" -ForegroundColor Red }
function Info ($m) { Write-Host "    $m" -ForegroundColor DarkGray }
function Assert-That([bool]$cond, [string]$desc) {
    if ($cond) { Ok $desc; $script:Pass++ } else { Bad $desc; $script:Fail++; $script:Failures += $desc }
}

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
function SqlRows([string]$q) { return @(MySql @('-e', $q) | Where-Object { $_ -notmatch '^mysql: \[Warning\]' } | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' }) }
function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('t10scope-' + [guid]::NewGuid().ToString('N') + '.sql')
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
function Api([string]$method, [string]$url, $body, [string]$user = 'superAdmin') {
    $headers = @{ Authorization = "Bearer $(TokenOf $user)" }
    try {
        if ($method -eq 'GET') { return Invoke-RestMethod "$BaseUrl$url" -Method Get -Headers $headers -TimeoutSec 60 }
        if ($null -eq $body) { return Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $headers -TimeoutSec 60 }
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
function CodeOf($res) { if ($res -and $null -ne $res.code) { return [int]$res.code } return -1 }
function MsgOf($res) { if ($res -and $res.msg) { return [string]$res.msg } return '' }
function RowsOf($res) { if ($res -and $res.rows) { return @($res.rows) } return @() }
function IdsOf($res) { return @(RowsOf $res | ForEach-Object { [string]$_.id } | Sort-Object) }

<# 权限态/数据范围切换一律走完整重新登录（DEV-ENV §6.47：/getInfo 只按集合变化写回缓存，不可靠）。
   ⚠ 必须**逐个账号**起子进程并只传一个用户名：`powershell -File` 不能把数组传给 [string[]] 参数
   （会把 "a,b,c" 当**单个**用户名 → 登录必失败 exit=1，本轮实测踩过）。 #>
function Relogin([string[]]$users) {
    foreach ($one in $users) {
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'oa-login.ps1') `
            -Users $one -CacheDir $CacheDir -MySqlCli $MySqlCli -Database $Database 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "重新登录 $one 失败（oa-login.ps1 exit=$LASTEXITCODE）" }
    }
}

<# 导出面取数：ExcelUtil 输出真 xlsx（Content-Type xlsx、首两字节 PK），必须解包 sheet1.xml 数数据行 #>
function ExportXlsx([string]$url, [string]$user) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $tmp = Join-Path $env:TEMP ('t10scope-' + [guid]::NewGuid().ToString('N') + '.xlsx')
    $out = & curl.exe -s -o $tmp -w '%{http_code}|%{content_type}' -H "Authorization: Bearer $(TokenOf $user)" "$BaseUrl$url" 2>&1
    $parts = ([string](@($out) | Select-Object -Last 1)) -split '\|'
    $http = $parts[0]
    $ctype = if ($parts.Count -gt 1) { $parts[1] } else { '' }
    $magic = ''; $dataRows = 0; $bodyHead = ''
    if (Test-Path $tmp) {
        $bytes = [System.IO.File]::ReadAllBytes($tmp)
        if ($bytes.Length -ge 2) { $magic = [System.Text.Encoding]::ASCII.GetString($bytes[0..1]) }
        if ($magic -eq 'PK') {
            $z = [System.IO.Compression.ZipFile]::OpenRead($tmp)
            try {
                $sheet = $z.Entries | Where-Object { $_.FullName -eq 'xl/worksheets/sheet1.xml' } | Select-Object -First 1
                if ($sheet) {
                    $sr = New-Object System.IO.StreamReader($sheet.Open(), [System.Text.Encoding]::UTF8)
                    $xml = $sr.ReadToEnd(); $sr.Close()
                    # Singleline 必须：<row> 与首个子 <c> 之间有换行
                    $rows = [regex]::Matches($xml, '<row [^>]*>(.*?)</row>', [System.Text.RegularExpressions.RegexOptions]::Singleline)
                    $dataRows = [Math]::Max(0, $rows.Count - 1)   # 首行是表头
                }
            } finally { $z.Dispose() }
        } else {
            $n = [Math]::Min(300, $bytes.Length)
            if ($n -gt 0) { $bodyHead = ([System.Text.Encoding]::UTF8.GetString($bytes[0..($n - 1)]) -replace "`r?`n", ' ') }
        }
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
    return [pscustomobject]@{ Http = $http; CType = $ctype; Magic = $magic; DataRows = $dataRows; BodyHead = $bodyHead }
}

# 数据范围切换一律**同时写哨兵**：任何中途崩溃都会留下哨兵，下一轮入口据此自愈还原
function SetScope([string]$scope) {
    $null = SqlFile "UPDATE sys_role SET data_scope='$scope', remark='$($script:Sentinel)' WHERE role_id='$($script:RoleId)';"
}
# 把观察者的部门临时搬走（基线既在内存、也落盘，见 SaveBaseline/LoadBaseline）
function MoveDept([string]$userId, [string]$deptId) {
    $null = SqlFile "UPDATE sys_user SET dept_id='$deptId' WHERE user_id='$userId';"
}
<#
  基线落盘（.cache/t10-scope-baseline.json）：中途崩溃后**下一轮入口**能据此还原，
  而不是依赖内存快照（内存快照在崩溃时就丢了，那正是本轮 run3 暴露的问题）。
#>
function SaveBaseline($data) {
    $f = Join-Path $CacheDir 't10-scope-baseline.json'
    ($data | ConvertTo-Json -Depth 6) | Set-Content -Path $f -Encoding UTF8
    return $f
}
function LoadBaseline() {
    $f = Join-Path $CacheDir 't10-scope-baseline.json'
    if (-not (Test-Path $f)) { return $null }
    try { return (Get-Content $f -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}
function DropBaseline() {
    $f = Join-Path $CacheDir 't10-scope-baseline.json'
    Remove-Item $f -Force -ErrorAction SilentlyContinue
}
# 给账号临时加一个 data_scope='1' 的第二个角色（并集用例）
function AddWideRole([string]$userId, [string]$roleId) {
    $null = SqlFile "INSERT IGNORE INTO sys_user_role(user_id, role_id) VALUES ('$userId', '$roleId');"
}

<#
  清理本脚本的全部历史夹具（宽前缀 T10S%），**子表 → 父表**（本批 FK 1451 的教训）：
  入口与收尾都调用 ⇒ 即使上一轮中途异常退出（例如 `$pid` 只读变量报错那次），下一轮也能自愈重跑。

  ⚠ 两个 MySQL 坑（都在这里踩过/绕开了，别改回去）：
    ① **不能自引用**：`DELETE FROM t_ctms_stock_in_item WHERE doc_id IN (SELECT … FROM t_ctms_stock_in_item)`
       会报 `ERROR 1093 You can't specify target table … for update in FROM clause`。
       所以"夹具单据"改从**表头仓库**定位（`t_ctms_stock_in.warehouse_id IN (夹具仓库)`）——
       子查询里出现的表与 DELETE 的目标表**不同名**；
    ② 顺序必须是 **流水/结存 → 变更历史 → 行项 → 表头 → 物料 → 类型/仓库/单位**：
       先删主数据会撞 FK 1451。
#>
function ClearFixtures() {
    $prodSub = "SELECT id FROM t_ctms_product WHERE code LIKE 'T10SP%'"
    $whSub   = "SELECT id FROM t_ctms_warehouse WHERE code LIKE 'T10SW%'"
    $docSub  = "SELECT id FROM t_ctms_stock_in WHERE warehouse_id IN ($whSub)"
    $null = SqlFile @"
DELETE FROM t_ctms_stock_ledger   WHERE product_id IN ($prodSub);
DELETE FROM t_ctms_stock          WHERE product_id IN ($prodSub);
DELETE FROM t_ctms_change_log     WHERE object_id IN ($docSub);
DELETE FROM t_ctms_stock_in_item  WHERE doc_id IN ($docSub);
DELETE FROM t_ctms_stock_in       WHERE warehouse_id IN ($whSub);
DELETE FROM t_ctms_product        WHERE code LIKE 'T10SP%';
DELETE FROM t_ctms_product_type   WHERE code LIKE 'T10ST%';
DELETE FROM t_ctms_warehouse      WHERE code LIKE 'T10SW%';
DELETE FROM t_ctms_uom            WHERE code LIKE 'T10SU%';
"@
}

# ================================================================ 0. 前置
Step '0. 前置：mysql / token / B4 表 / common 角色基线'
Assert-That (Test-Path $MySqlCli) "mysql 客户端存在：$MySqlCli"
$roleId = Sql "SELECT role_id FROM sys_role WHERE role_key='$SentinelRole'"
Assert-That ($roleId -ne '') "借用角色 $SentinelRole 存在（role_id=$roleId）"
$script:RoleId = $roleId
$scopeBase = Sql "SELECT data_scope FROM sys_role WHERE role_id='$roleId'"
$remarkBase = Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$roleId'"
$grantsBase = @(SqlRows "SELECT menu_id FROM sys_role_menu WHERE role_id='$roleId'")
Info "基线：data_scope=$scopeBase remark=[$remarkBase] 已授权菜单行=$($grantsBase.Count)"
Assert-That ($grantsBase.Count -eq 0) "基线 sys_role_menu 中 $SentinelRole 无菜单行（本脚本的还原目标 = 清空）"

$Users = @('lina', 'wangqiang', 'zhangwei', 'zhaomin')
foreach ($u in $Users) { Assert-That (Test-Path (Join-Path $CacheDir "token-$u.txt")) "token 文件存在：$u" }
Assert-That (Test-Path (Join-Path $CacheDir "token-$Admin.txt")) "token 文件存在：$Admin"
$tables = Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME IN ('t_ctms_stock_in','t_ctms_stock_ledger','t_ctms_stock')"
Assert-That ($tables -eq '3') "B4 三张关键表在位（实际 $tables/3）"
$probe = Api 'GET' '/stk/stock/list?pageSize=1' $null $Admin
Assert-That ((CodeOf $probe) -eq 200) "后端 8080 与 /stk/stock/list 在线（code=$(CodeOf $probe)）"

# ================================================================ 1. 入口自愈
Step '1. 入口自愈（哨兵 / 授权 / 调岗 任一残留 → 按**落盘基线**还原）'
$bl = LoadBaseline
$sentinelNow = Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$roleId'"
$grantsNow = [int](Sql "SELECT COUNT(*) FROM sys_role_menu WHERE role_id='$roleId'")
$staleDept = $false
if ($bl -ne $null -and $bl.depts) {
    foreach ($u in @('zhangwei', 'zhaomin')) {
        $want = [string]$bl.depts.$u
        if ($want -ne '' -and (Sql "SELECT dept_id FROM sys_user WHERE user_name='$u'") -ne $want) { $staleDept = $true }
    }
}
if ($bl -ne $null -or $sentinelNow -eq $script:Sentinel -or $grantsNow -gt 0 -or $staleDept) {
    $restoreScope = if ($bl -ne $null -and $bl.scope -ne $null) { [string]$bl.scope } else { $scopeBase }
    $restoreRemark = if ($bl -ne $null -and $bl.remark -ne $null) { [string]$bl.remark } else { $remarkBase }
    $rRemark = if ($restoreRemark -eq '') { 'NULL' } else { "'$restoreRemark'" }
    $null = SqlFile "UPDATE sys_role SET data_scope='$restoreScope', remark=$rRemark WHERE role_id='$roleId';"
    $null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$roleId';"
    if ($bl -ne $null -and $bl.depts) {
        foreach ($u in @('zhangwei', 'zhaomin')) {
            $want = [string]$bl.depts.$u
            if ($want -ne '') { MoveDept (Sql "SELECT user_id FROM sys_user WHERE user_name='$u'") $want }
        }
    }
    DropBaseline
    $scopeBase = $restoreScope; $remarkBase = $restoreRemark; $grantsBase = @()
    Info "发现上轮残留（哨兵=[$sentinelNow] 授权行=$grantsNow 调岗残留=$staleDept）→ 已按基线还原并清除基线文件"
}
Assert-That ((Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$roleId'") -eq $remarkBase) "进入时哨兵已清（remark 与基线一致）"
Assert-That ((Sql "SELECT data_scope FROM sys_role WHERE role_id='$roleId'") -eq $scopeBase) "进入时 data_scope 与基线一致（$scopeBase）"
ClearFixtures
$leftoverProd = Sql "SELECT COUNT(*) FROM t_ctms_product WHERE code LIKE 'T10SP%'"
$leftoverWh = Sql "SELECT COUNT(*) FROM t_ctms_warehouse WHERE code LIKE 'T10SW%'"
Assert-That ($leftoverProd -eq '0' -and $leftoverWh -eq '0') "入口已清理历史 T10S* 夹具（剩余物料 $leftoverProd / 仓库 $leftoverWh）"

# ================================================================ 2. 夹具
Step '2. 夹具：单位 / 仓库 / 类型 / 4 个物料（ASCII 前缀 T10S*）'
$S = (Get-Date -Format 'HHmmss')
$UomCode = "T10SU$S"; $WhCode = "T10SW$S"; $TypeCode = "T10ST$S"
$ProdCodes = @{}; $DocIds = @{}; $ProdIds = @{}
$null = Api 'POST' '/ctms/uom' @{ code = $UomCode; name = "范围单位-$S"; decimals = 3; enableFlag = '1' } $Admin
$UomId = Sql "SELECT id FROM t_ctms_uom WHERE code='$UomCode'"
Assert-That ($UomId -ne '') "夹具单位已建（$UomCode）"
$null = Api 'POST' '/ctms/warehouse' @{ code = $WhCode; name = "范围仓-$S"; enableFlag = '1' } $Admin
$WhId = Sql "SELECT id FROM t_ctms_warehouse WHERE code='$WhCode'"
Assert-That ($WhId -ne '') "夹具仓库已建（$WhCode）"
$null = Api 'POST' '/ctms/product-type' @{ code = $TypeCode; name = "范围类型-$S"; enableFlag = '1' } $Admin
$TypeId = Sql "SELECT id FROM t_ctms_product_type WHERE code='$TypeCode'"
Assert-That ($TypeId -ne '') "夹具物料类型已建（$TypeCode）"
foreach ($u in $Users) {
    $code = "T10SP$S$($u.ToUpper())"   # ⚠ 用全名：zhangwei/zhaomin 首字母都是 Z，用首字母会撞号（本轮实测只剩 3 个物料）
    $null = Api 'POST' '/ctms/product' @{ code = $code; name = "范围物料-$u-$S"; productTypeId = $TypeId; uomId = $UomId; safetyStock = 0; enableFlag = '1' } $Admin
    $prodId = Sql "SELECT id FROM t_ctms_product WHERE code='$code'"
    Assert-That ($prodId -ne '') "夹具物料已建（$code → $u）"
    $ProdCodes[$u] = $code; $ProdIds[$u] = $prodId
}

# 账号 → 部门/用户ID（只读；下面两处临时调岗与收尾还原都用它）
$deptOf = @{}; $userIdOf = @{}
foreach ($u in $Users) {
    $row = Sql "SELECT CONCAT(user_id,'|',dept_id) FROM sys_user WHERE user_name='$u'"
    $userIdOf[$u] = ($row -split '\|')[0]; $deptOf[$u] = ($row -split '\|')[1]
}
$rootDept = Sql "SELECT dept_id FROM sys_dept WHERE parent_id='0' ORDER BY dept_id LIMIT 1"
Info "账号→部门：lina=$($deptOf['lina']) wangqiang=$($deptOf['wangqiang']) zhangwei=$($deptOf['zhangwei']) zhaomin=$($deptOf['zhaomin'])｜根部门=$rootDept"

# ================================================================ 3. 借授权 + 基线落盘 + 哨兵 + 临时调岗
# ⚠ 顺序很重要：**先借授权再建单据**（本轮实测踩过：先建单会 403），授权态变化后必须完整重新登录。
Step '3. 借 common 角色授权（B4 全部菜单行）+ 基线落盘 + 开哨兵 + 两处临时调岗'
SaveBaseline @{ scope = $scopeBase; remark = $remarkBase
    depts = @{ zhangwei = $deptOf['zhangwei']; zhaomin = $deptOf['zhaomin'] } } | Out-Null
Info "基线已落盘（.cache/t10-scope-baseline.json）：scope=$scopeBase remark=[$remarkBase] + zhangwei/zhaomin 的部门"
$null = SqlFile @"
UPDATE sys_role SET remark='$script:Sentinel' WHERE role_id='$roleId';
INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT '$roleId', menu_id FROM sys_menu
 WHERE menu_id IN ('9F2C0000000000000000000000000002','9F2C0000000000000000000000000003')
    OR menu_id BETWEEN '9F2C0000000000000000000000000101' AND '9F2C0000000000000000000000000292';
"@
$granted = Sql "SELECT COUNT(*) FROM sys_role_menu WHERE role_id='$roleId'"
Assert-That ([int]$granted -ge 121) "已借 B4 菜单授权 $granted 行（≥121）"
Assert-That ((Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$roleId'") -eq $script:Sentinel) "哨兵已开（remark=$script:Sentinel）"

# 授权态变了，先完整重新登录（不重新登录则建单仍 403 —— 本轮实测踩过）
Relogin $Users

# ================================================================ 3b. 夹具单据（4 个账号各 1 张，审核即过账）
Step '3b. 4 个账号各建 1 张入库单并审核过账（登记 dept_id/create_id 快照 → 同时产出结存与流水）'
foreach ($u in $Users) {
    $r = Api 'POST' '/stk/in-order' @{ warehouseId = $WhId; inType = '采购入库'; docDate = (Get-Date -Format 'yyyy-MM-dd')
        items = @(@{ productId = $ProdIds[$u]; qty = 5; unitPrice = 2 }) } $u
    if ((CodeOf $r) -ne 200) { Bad "$u 建入库单失败：code=$(CodeOf $r) msg=$(MsgOf $r)"; continue }
    $docId = [string]$r.data.id
    $null = Api 'POST' "/stk/in-order/submit/$docId" $null $u
    $null = Api 'POST' "/stk/in-order/approve/$docId" $null $u
    $DocIds[$u] = $docId
}
foreach ($u in $Users) {
    if (-not $DocIds.ContainsKey($u)) { Assert-That $false "$u 的入库单未建成"; continue }
    $snap = Sql "SELECT CONCAT(dept_id,'|',create_id,'|',posted) FROM t_ctms_stock_in WHERE id='$($DocIds[$u])'"
    Assert-That (($snap -split '\|')[2] -eq '1') "$u 的单已过账（posted=1，$snap）"
}

# ================================================================ 3c. 临时调岗（**必须在夹具单据之后**）
# ⚠ 顺序理由：单据的 dept_id 是**创建时快照**，本脚本正是要验证这一点 ——
#   若先调岗再建单（本轮 run4 踩过），zhaomin 的单会带上财务部，'3' 与 '5' 就不可判别了。
Step '3c. 两处临时调岗（观察者调岗在建单之后，才能兼证"创建时快照不回溯"）'
# 区分 '4' 与 '3'：观察者 zhangwei 临时移到根部门
MoveDept $userIdOf['zhangwei'] $rootDept
# 区分 '3' 与 '5'：zhaomin 临时移到 lina 的部门（同部门里应有"别人创建"的行）
MoveDept $userIdOf['zhaomin'] $deptOf['lina']
Assert-That ((Sql "SELECT dept_id FROM sys_user WHERE user_name='zhangwei'") -eq $rootDept) "zhangwei 已临时调到根部门"
Assert-That ((Sql "SELECT dept_id FROM sys_user WHERE user_name='zhaomin'") -eq $deptOf['lina']) "zhaomin 已临时调到财务部（lina 部门）"
Relogin $Users
Assert-That ((Sql "SELECT dept_id FROM t_ctms_stock_in WHERE id='$($DocIds['zhaomin'])'") -ne $deptOf['lina']) "zhaomin 的夹具单仍保留创建时的市场部 dept_id（快照不因调岗回溯）"

# ================================================================ 4. 四档矩阵
function ListDocIds([string]$user) { return IdsOf (Api 'GET' '/stk/in-order/list?pageSize=200' $null $user) }
function ListStockProducts([string]$user) { return @(RowsOf (Api 'GET' '/stk/stock/list?pageSize=200' $null $user) | ForEach-Object { [string]$_.productId } | Sort-Object) }
function ListLedgerProducts([string]$user) { return @(RowsOf (Api 'GET' '/stk/ledger/list?pageSize=200' $null $user) | ForEach-Object { [string]$_.productId } | Sort-Object -Unique) }
function Set-Of([string[]]$arr) { return (@($arr) | Where-Object { $_ -ne '' } | Sort-Object -Unique) }

# —— 夹具作用域断言 ——
# 共享库里同时存在**别人的数据**（t12/t13 的 E2E 夹具、历史探针等），所以断言只看"我的夹具"
# 是否命中（`MineOf`），别人的一概忽略但在 Info 里报出计数；越权断言用"某个夹具 id 不得出现"。
$allProd = @($Users | ForEach-Object { $ProdIds[$_] })
$allDocs = @($Users | ForEach-Object { $DocIds[$_] })
function MineOf([string[]]$returned, [string[]]$mine) { return @($returned | Where-Object { $mine -contains $_ } | Sort-Object -Unique) }
function ForeignOf([string[]]$returned, [string[]]$mine) { return @($returned | Where-Object { $mine -notcontains $_ }).Count }
function InSet([string]$id, [string[]]$set) { return (@($set) -contains $id) }

Step "4a. data_scope='1'（全部）：观察者 lina 应看到 4 张夹具单 / 4 个夹具物料"
SetScope '1'
Relogin $Users
$d1 = Set-Of (ListDocIds 'lina'); $p1 = Set-Of (ListStockProducts 'lina'); $l1 = Set-Of (ListLedgerProducts 'lina')
$m1d = MineOf $d1 $allDocs; $m1p = MineOf $p1 $allProd; $m1l = MineOf $l1 $allProd
Info "全部档：单据返回 $($d1.Count) 行（夹具 $($m1d.Count) / 他人 $(ForeignOf $d1 $allDocs)）"
Assert-That ($m1d.Count -eq 4) "全部档：4 张夹具单全部可见（实际 $($m1d.Count)）"
Assert-That ($m1p.Count -eq 4) "全部档：4 个夹具物料结存可见（实际 $($m1p.Count)）"
Assert-That ($m1l.Count -eq 4) "全部档：4 个夹具物料流水可见（实际 $($m1l.Count)）"

Step "4b. data_scope='3'（本部门）：lina（财务部）只看得到财务部那 1 张夹具单"
SetScope '3'
Relogin $Users
$d3 = Set-Of (ListDocIds 'lina'); $p3 = Set-Of (ListStockProducts 'lina')
$m3d = MineOf $d3 $allDocs; $m3p = MineOf $p3 $allProd
Assert-That ($m3d.Count -eq 1 -and (($m3d -join ',') -eq $DocIds['lina'])) "本部门档：夹具单只命中 lina 那张（实际 $($m3d.Count)）"
Assert-That (-not (InSet $DocIds['wangqiang'] $d3)) "本部门档：看不到研发部的夹具单（越权被拦截）"
Assert-That ($m3p.Count -eq 1 -and (($m3p -join ',') -eq $ProdIds['lina'])) "本部门档：夹具结存只命中 lina 的物料（实际 $($m3p.Count)）"

Step "4c. data_scope='5'（本人）：zhaomin 只看到自己创建的那张（其单的 dept 是市场部快照）"
SetScope '5'
Relogin $Users
$d5 = Set-Of (ListDocIds 'zhaomin'); $p5 = Set-Of (ListStockProducts 'zhaomin')
$m5d = MineOf $d5 $allDocs; $m5p = MineOf $p5 $allProd
Assert-That ($m5d.Count -eq 1 -and (($m5d -join ',') -eq $DocIds['zhaomin'])) "本人档：夹具单只命中 zhaomin 自己那张（实际 $($m5d.Count)）"
Assert-That (-not (InSet $DocIds['lina'] $d5)) "本人档：看不到同部门的 lina 那张（'5' ≠ '3'，档位可判别）"
Assert-That ($m5p.Count -eq 1 -and (($m5p -join ',') -eq $ProdIds['zhaomin'])) "本人档：夹具结存只命中 zhaomin 的物料（实际 $($m5p.Count)）"

Step "4d. 同一观察者切回 data_scope='3'（zhaomin 现属财务部）：看得到 lina 那张、看不到自己的"
SetScope '3'
Relogin $Users
$d3b = Set-Of (ListDocIds 'zhaomin'); $m3b = MineOf $d3b $allDocs
Assert-That ($m3b.Count -eq 1 -and (($m3b -join ',') -eq $DocIds['lina'])) "本部门档（调岗后）夹具单 = lina 那张（实际 $($m3b.Count)）"
Assert-That (-not (InSet $DocIds['zhaomin'] $d3b)) "调岗后仍看不到自己那张（dept 是创建时市场部快照 ⇒ 快照不回溯）"

Step "4e. data_scope='4'（本部门及下级）@ zhangwei（临时在根部门）：4 张全可见；切回 '3' 变 0 张"
SetScope '4'
Relogin $Users
$d4 = Set-Of (ListDocIds 'zhangwei'); $p4 = Set-Of (ListStockProducts 'zhangwei'); $l4 = Set-Of (ListLedgerProducts 'zhangwei')
$m4d = MineOf $d4 $allDocs; $m4p = MineOf $p4 $allProd; $m4l = MineOf $l4 $allProd
Assert-That ($m4d.Count -eq 4) "本部门及下级档：根部门观察者命中全部 4 张夹具单（实际 $($m4d.Count)）"
Assert-That ($m4p.Count -eq 4) "本部门及下级档：夹具结存 4 个物料（实际 $($m4p.Count)）"
Assert-That ($m4l.Count -eq 4) "本部门及下级档：夹具流水 4 个物料（实际 $($m4l.Count)）"
SetScope '3'
Relogin $Users
$d3c = Set-Of (ListDocIds 'zhangwei'); $m3c = MineOf $d3c $allDocs
Assert-That ($m3c.Count -eq 0) "本部门档 @ 根部门观察者 = 0 张夹具单（与 '4' 的 4 张可判别，实际 $($m3c.Count)）"

Step "4f. 多角色取并集：zhaomin 在 common('5') 之外再加一个 data_scope='1' 的角色 → 4 张全可见"
$wideRole = Sql "SELECT role_id FROM sys_role WHERE role_key='bm' LIMIT 1"
if ($wideRole -eq '') { $wideRole = Sql "SELECT role_id FROM sys_role WHERE role_key<>'$SentinelRole' AND data_scope='1' LIMIT 1" }
SetScope '5'
AddWideRole $userIdOf['zhaomin'] $wideRole
Relogin $Users
$dUnion = Set-Of (ListDocIds 'zhaomin'); $mUnion = MineOf $dUnion $allDocs
Assert-That ($mUnion.Count -eq 4) "并集档：common('5') ∪ 第二角色('1') 命中全部 4 张（实际 $($mUnion.Count)）"
$null = SqlFile "DELETE FROM sys_user_role WHERE user_id='$($userIdOf['zhaomin'])' AND role_id='$wideRole';"
Assert-That ((Sql "SELECT COUNT(*) FROM sys_user_role WHERE user_id='$($userIdOf['zhaomin'])' AND role_id='$wideRole'") -eq '0') "并集用例的第二角色已摘除"

# ================================================================ 5. 导出与越权
Step "5. 导出与列表同范围同筛选 + 范围外 403（先回到 '3'）"
SetScope '3'
Relogin $Users
$listCount = (Set-Of (ListDocIds 'lina')).Count
$exp = ExportXlsx '/stk/in-order/export' 'lina'
Assert-That ($exp.Http -eq '200') "导出：HTTP 200（实际 $($exp.Http)）"
Assert-That ($exp.Magic -eq 'PK') "导出：响应是 xlsx（首两字节 PK，实际 '$($exp.Magic)'）"
Assert-That ($exp.DataRows -eq $listCount) "导出与列表同范围：导出行数 $($exp.DataRows) == 列表 total $listCount"

$outDoc = $DocIds['wangqiang']
$detail = Api 'GET' "/stk/in-order/$outDoc" $null 'lina'
Assert-That ((CodeOf $detail) -eq 403) "范围外单据详情：业务码 403（不是 500；实际 $(CodeOf $detail) msg=$(MsgOf $detail)）"
$outKey = Api 'GET' "/stk/stock/detail?productId=$($ProdIds['wangqiang'])&warehouseId=$WhId" $null 'lina'
Assert-That ((CodeOf $outKey) -eq 403) "范围外结存详情：业务码 403（实际 $(CodeOf $outKey) msg=$(MsgOf $outKey)）"
$outLedger = RowsOf (Api 'GET' "/stk/ledger/list?productId=$($ProdIds['wangqiang'])&warehouseId=$WhId&pageSize=50" $null 'lina')
Assert-That ($outLedger.Count -eq 0) "范围外流水下钻：返回 0 行（实际 $($outLedger.Count)）"

# ================================================================ 6. 收尾还原
Step '6. 收尾：按基线还原 data_scope / 哨兵 / 授权 / 部门，删夹具，断言零残留'
$rRemark = if ($remarkBase -eq '') { 'NULL' } else { "'$remarkBase'" }
$null = SqlFile "UPDATE sys_role SET data_scope='$scopeBase', remark=$rRemark WHERE role_id='$roleId';"
$null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$roleId';"
MoveDept $userIdOf['zhangwei'] $deptOf['zhangwei']
MoveDept $userIdOf['zhaomin'] $deptOf['zhaomin']
DropBaseline
Assert-That ((Sql "SELECT COUNT(*) FROM sys_role_menu WHERE role_id='$roleId'") -eq '0') "$SentinelRole 授权已还原为空"
Assert-That ((Sql "SELECT data_scope FROM sys_role WHERE role_id='$roleId'") -eq $scopeBase) "data_scope 已还原为基线 '$scopeBase'"
Assert-That ((Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$roleId'") -eq $remarkBase) "哨兵 remark 已清（基线 [$remarkBase]）"
Assert-That ((Sql "SELECT dept_id FROM sys_user WHERE user_name='zhangwei'") -eq $deptOf['zhangwei']) "zhangwei 部门已还原"
Assert-That ((Sql "SELECT dept_id FROM sys_user WHERE user_name='zhaomin'") -eq $deptOf['zhaomin']) "zhaomin 部门已还原"

if (-not $KeepFixtures) { ClearFixtures }
$leftStock = Sql "SELECT COUNT(*) FROM t_ctms_stock WHERE product_id IN (SELECT id FROM t_ctms_product WHERE code LIKE 'T10SP%')"
$leftLedger = Sql "SELECT COUNT(*) FROM t_ctms_stock_ledger WHERE product_id IN (SELECT id FROM t_ctms_product WHERE code LIKE 'T10SP%')"
$leftDoc = Sql "SELECT COUNT(*) FROM t_ctms_stock_in WHERE id IN (SELECT DISTINCT doc_id FROM t_ctms_stock_in_item WHERE product_id IN (SELECT id FROM t_ctms_product WHERE code LIKE 'T10SP%'))"
$leftProd = Sql "SELECT COUNT(*) FROM t_ctms_product WHERE code LIKE 'T10SP%'"
$leftWh = Sql "SELECT COUNT(*) FROM t_ctms_warehouse WHERE code LIKE 'T10SW%'"
$leftUom = Sql "SELECT COUNT(*) FROM t_ctms_uom WHERE code LIKE 'T10SU%'"
$leftType = Sql "SELECT COUNT(*) FROM t_ctms_product_type WHERE code LIKE 'T10ST%'"
Info "残留：结存 $leftStock / 流水 $leftLedger / 单据 $leftDoc / 物料 $leftProd / 仓库 $leftWh / 单位 $leftUom / 类型 $leftType（均应 0）"
Assert-That ($leftStock -eq '0' -and $leftLedger -eq '0' -and $leftDoc -eq '0' -and $leftProd -eq '0' -and $leftWh -eq '0' -and $leftUom -eq '0' -and $leftType -eq '0') '夹具零残留（结存/流水/单据/物料/仓库/单位/类型 全 0）'
Assert-That ((Sql "SELECT COUNT(*) FROM sys_role WHERE data_scope='5'") -eq '0') '全库无 data_scope=5 残留（§6.48 硬要求）'

Write-Host ''
Write-Host ('=' * 78)
if ($script:Fail -eq 0) {
    Write-Host "数据范围四档矩阵：通过 $($script:Pass) 项，失败 0 项。" -ForegroundColor Green
    exit 0
}
Write-Host "数据范围四档矩阵：通过 $($script:Pass) 项，失败 $($script:Fail) 项。" -ForegroundColor Red
$script:Failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
exit 1
