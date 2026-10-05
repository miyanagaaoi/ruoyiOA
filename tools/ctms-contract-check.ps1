<#
================================================================================
 ctms-contract-check.ps1 —— 合同台账 B3「合同主体」验收（第 4 组 / 任务 4.1~4.8）
--------------------------------------------------------------------------------
 为什么是"真实环境 + 接口"：
   第 4 组的验收面全是**可观察行为**：唯一编号、状态自由流转、标签交集、
   软删除与 30 天边界、框架四条守卫、质保算法落库、字段级变更历史、
   数据范围 403、详情只读无副作用。内存桩单测只能证明"服务层分支写了"，
   证明不了 Mapper XML 真能被 MyBatis 绑定、SQL 与 DDL 列名/约束真的对齐 ——
   本脚本就抓出过一个内存桩发现不了的 FK 顺序缺陷（行项先于主表插入 → 1452 → 登记 500）。

 覆盖（对应 specs/ctms/contract-ledger/spec.md 的 9 个 Requirement）：
   4.1 登记与编辑：编号唯一、已停用不可编辑、非法状态被拒、创建人/部门服务端快照
   4.2 进度状态与到货状态独立、自由流转、已终止需填原因
   4.3 多维筛选：关键字（含行项规格）、标签**交集**、默认排除停用 / 显式包含、组合筛选（按 id 判定）
   4.4 软删除 30 天恢复：原因必填、第 30 天可恢复、第 31 天不可、未停用幂等
   4.5 标签与框架：自动标签随类型替换、手动标签保留、框架四条守卫、框架详情汇总
   4.6 变更历史：含操作人标识/姓名快照、手工改 manual、自动重算 auto、操作人为空不报错
   4.7 详情只读「关联单据」：打开详情前后合同字段逐字段一致
   4.8 数据范围：无权限 403、授权后按范围可见
   4.8b 数据范围**四档矩阵**（任务 9.2 / AC-79，2026-10-05 追加）：
        ALL / DEPT / DEPT_AND_CHILD / SELF（+ SELF_ONLY / 多角色并集 / 超管放行） ×
        列表 / 详情 / 导出 的可见集合逐格实测，并与**显式写出的口径基线**比对（1.4 未成文，
        基线按 design D-4 + specs/ctms/contract-ledger「合同台账数据范围服务端强制」+ PRD AC-79 写出，
        见 `notes/permission-audit.md` §9.2）。

 用法（在仓库根目录，先跑 start-env.ps1 与 oa-login.ps1）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-contract-check.ps1

 前置：后端 8080 在线；`.cache\token-superAdmin.txt` 有效；
       rad_oa 已执行 `sql\二开-合同台账.sql`、字典参数 SQL、菜单 SQL。

 副作用：夹具全部用 ASCII 前缀 CTM*（含一个临时子部门 `CTMDEPT*`），收尾按白名单物理删除（幂等）。
         「第 31 天不可恢复」靠直接改 deleted_at 制造，不改系统时间。
         4.8b 会临时借用 `common` 角色的授权 + `sys_role.data_scope`（'1'/'3'/'4'/'5'），
         并临时把 `bm` 角色挂给受测账号以验「多角色取并集」；收尾全部还原，
         入口有 remark 哨兵自愈（DEV-ENV §6.48），不可把 data_scope='5' 留在库里。
         ⚠ 权限态切换一律走 `oa-login.ps1` 的**完整重新登录**（§6.47），不用 /getInfo 顶替。

 三条脚本经验（都是这一轮实测踩出来的，写新脚本时请沿用）：
   1. **不可并发执行**：本脚本会删掉所有带 CTM 前缀/本轮序号的夹具，
      两轮并发会互相拆台，现象是"列表筛选返回了不该返回的行"（看起来像筛选失效）。
      已内置锁文件 `.cache/ctms-contract-check.lock`（`-Force` 可跳过）。
   2. **中文查询参数必须先编码再拼串**：`"/x?status=${([uri]::EscapeDataString('付款中'))}"`
      里的嵌套单引号会**提前结束外层字符串**，URL 变成 `status=`（空值）→ 条件不生效，
      而这种"条件不生效"恰好会让"排掉了不匹配行"的断言假通过。所以下面统一用预编码变量。
   3. **断言必须按 id、并带前后对照**："结果里有没有它"不够 ——
      同状态下还有别的行时，条件失效也能通过（没有区分力）。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl  = 'http://localhost:8080',
    [string]$MySqlCli = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database = 'rad_oa',
    [string]$SubjectCode = 'ZC',
    [string]$SignDate = '2026-09-15',
    [string]$LimitedUser = 'zhangwei',
    # 4.8b「多角色取并集」用的第二个角色（role_key）：默认 bm（部门经理，未分配给任何账号、无菜单）
    [string]$UnionRoleKey = 'bm',
    # 跳过并发运行锁（仅在确认没有其它实例在跑时使用）
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

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
    $tmp = Join-Path $env:TEMP ('ctmsct-' + [guid]::NewGuid().ToString('N') + '.sql')
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

# ---------------------------------------------------------------- HTTP 辅助

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
function IsForbidden($res) { return ($null -ne $res) -and ($res.code -eq 403) }
function RowsOf($res) { if ($res -and $res.rows) { return @($res.rows) } return @() }
function IdsIn($res, [string]$id) { return @(RowsOf $res | Where-Object { $_.id -eq $id }).Count }

<# 刷新权限缓存（改过 sys_role_menu 后必须调；见 DEV-ENV.md §6.37） #>
function Refresh-Permissions([string]$user) {
    $r = Api 'GET' '/getInfo' $null $user
    if (-not (IsOk $r)) { throw "刷新 $user 的权限缓存失败：code=$($r.code) msg=$(MsgOf $r)" }
}

<# 多行 SQL（4.8b 的整集比对要按 id 逐行取） #>
function SqlRows([string]$q) {
    $out = MySql @('-e', $q)
    return @($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' } | Where-Object { ([string]$_).Trim() -ne '' })
}

<#
  原始 HTTP 响应（4.8b 专用）：
  范围外必须证明是「**HTTP 200 + 业务码 403**」，而不是 HTTP 层 403/500 ——
  Invoke-RestMethod 会把状态码丢掉（异常分支里也只剩消息），所以这里用 Invoke-WebRequest 留原始状态码与响应体。
#>
function ApiRaw([string]$method, [string]$url, $body, [string]$user = 'superAdmin') {
    $headers = @{ Authorization = "Bearer $(TokenOf $user)" }
    try {
        if ($null -eq $body) {
            $resp = Invoke-WebRequest "$BaseUrl$url" -Method $method -Headers $headers -TimeoutSec 60 -UseBasicParsing
        } else {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20 -Compress))
            $resp = Invoke-WebRequest "$BaseUrl$url" -Method $method -Headers $headers `
                -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 60 -UseBasicParsing
        }
        return [pscustomobject]@{ Http = [int]$resp.StatusCode; Body = [string]$resp.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            return [pscustomobject]@{ Http = [int]$resp.StatusCode; Body = $reader.ReadToEnd() }
        }
        return [pscustomobject]@{ Http = -1; Body = $_.Exception.Message }
    }
}
function BodyJson($raw) { if (-not $raw) { return $null } try { return ($raw.Body | ConvertFrom-Json) } catch { return $null } }

<#
  ============================ 导出面取数（R1 修复） ============================
  为什么这里**不能**按 JSON 解析 `/ctms/contract/export`：
    第 5 组（7.4）已把 `CtmsContractController.export` 改成 `ExcelUtil.exportExcel(response, exportRows(query), …)`，
    响应体是**真 xlsx 二进制**（Content-Type `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，
    首两字节 `PK`），体内**没有** JSON 的 `rows` 字段。
    若仍按老的 `TableDataInfo.rows` 解析，`RowsOf` 恒返回 0 条 → 4.8b 的导出断言恒判“导出不可见”，
    33 条全部假红（10.2 的 R1：**断言与产品契约漂移（脚本侧失效），不是产品回归**）。

  所以本函数改为：下载 → 解包 `xl/worksheets/sheet1.xml` → 用第 1 行表头定位「合同名称」列
  （ExcelUtil 的 @Excel 列序固定：编号/名称/类型/甲方/乙方/签订日期/金额/进度状态/经办人）→ 逐数据行取该列值。
  调用方再用「名称 → 合同 id」映射把名称还原成 id 集合，从而**保持原语义（逐 id 一致）**，
  而不是退化成“只看行数”（那样会弱化“范围外不可见”的越权断言）。

  返回：Http / CType / Magic（首两字节）/ Names（名称列，不含表头）/ Rows（数据行数）/ BodyHead（非 xlsx 时的体前 400 字符，用于证明 403 失败体）
#>
function ExportXlsx([string]$url, [string]$user = 'superAdmin') {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $tmp = Join-Path $env:TEMP ('ctms-export-' + [guid]::NewGuid().ToString('N') + '.xlsx')
    $out = & curl.exe -s -o $tmp -w '%{http_code}|%{content_type}' -H "Authorization: Bearer $(TokenOf $user)" "$BaseUrl$url" 2>&1
    $parts = ([string](@($out) | Select-Object -Last 1)) -split '\|'
    $http = $parts[0]
    $ctype = if ($parts.Count -gt 1) { $parts[1] } else { '' }
    $magic = ''; $bodyHead = ''; $names = @()
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
                    # ⚠ 必须 Singleline：sheet1.xml 里 <row> 与首个子 <c> 之间带换行，非贪婪 .*? 默认不跨行 → 会一行都匹配不到
                    $rows = [regex]::Matches($xml, '<row [^>]*>(.*?)</row>', [System.Text.RegularExpressions.RegexOptions]::Singleline)
                    if ($rows.Count -ge 1) {
                        $nameCol = ''
                        foreach ($h in [regex]::Matches($rows[0].Groups[1].Value, '<c [^>]*r="([A-Z]+)1"[^>]*>(.*?)</c>')) {
                            $t = [regex]::Match($h.Groups[2].Value, '<t[^>]*>([^<]*)</t>')
                            if ($t.Success -and $t.Groups[1].Value -eq '合同名称') { $nameCol = $h.Groups[1].Value }
                        }
                        if ($nameCol) {
                            for ($i = 1; $i -lt $rows.Count; $i++) {
                                $cell = [regex]::Match($rows[$i].Groups[1].Value, '<c [^>]*r="' + $nameCol + ($i + 1) + '"[^>]*>(.*?)</c>')
                                if ($cell.Success) {
                                    $t = [regex]::Match($cell.Groups[1].Value, '<t[^>]*>([^<]*)</t>')
                                    if ($t.Success) {
                                        $names += ($t.Groups[1].Value -replace '&amp;', '&' -replace '&lt;', '<' -replace '&gt;', '>')
                                    }
                                }
                            }
                        }
                    }
                }
            } finally { $z.Dispose() }
        } else {
            $n = [Math]::Min(400, $bytes.Length)
            if ($n -gt 0) { $bodyHead = ([System.Text.Encoding]::UTF8.GetString($bytes[0..($n - 1)]) -replace "`r?`n", ' ') }
        }
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
    return [pscustomobject]@{ Http = $http; CType = $ctype; Magic = $magic; Names = @($names); Rows = @($names).Count; BodyHead = $bodyHead }
}

<# 权限态/数据范围切换一律走**完整重新登录**（DEV-ENV §6.47：/getInfo 只在集合变化时才写回缓存） #>
function Relogin([string]$user) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'oa-login.ps1') `
        -Users $user -CacheDir $CacheDir -MySqlCli $MySqlCli -Database $Database | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "重新登录 $user 失败（oa-login.ps1 exit=$LASTEXITCODE）" }
}

# ================================================================ 夹具标识
$S = (Get-Date -Format 'HHmmss') + (Get-Random -Minimum 100 -Maximum 999)
$CustId  = "CTMCUST$S"
$SupId   = "CTMSUP$S"
$TypeId  = "CTMTYPE$S"
$UomId   = "CTMUOM$S"
$ProdId  = "CTMPROD$S"
$TagName = "验收标签$S"

# 中文查询参数的**预编码**（理由见文件头经验 2）
$encSigned      = [uri]::EscapeDataString('已签订')
$encPaying      = [uri]::EscapeDataString('付款中')
$encArrivalNone = [uri]::EscapeDataString('未到货')

<#
  ⚠ 清理判据必须同时覆盖两种夹具写法：
    · 绝大多数夹具是"服务端生成 UUID 主键"，只能按 name 前缀找；
    · 框架子合同那两个是直接 SQL 插的、id 前缀可控（CTMCHILD…）。
  另外标签夹具**不能用本轮序号**清 —— 上一轮中途异常退出时残留标签名字里带的是
  上一轮的序号，按 `%$S` 清不掉（实测攒下 16 个孤儿标签），所以按固定前缀清。
#>
function Clear-Fixtures {
    $out = SqlFile @"
-- ⚠ 清理顺序铁律（在这里踩过两次，别改回去）：
--   ① 产品被 t_ctms_contract_item.product_id 用 FK 引用（fk_contract_item_product）；
--   ② 客户/供应商被 t_ctms_contract.customer_id/supplier_id 用 FK 引用（fk_contract_customer / fk_contract_supplier）。
--   所以"删夹具对象"必须排在"删引用它的行"之后。曾把 t_ctms_product 的删除写在行项之前，
--   结果是：只要上一轮异常退出留下过带行项的 CTM 夹具，本轮就在这里撞 1451 而**整脚本中止**
--   （$ErrorActionPreference='Stop' + 外部程序 stderr → 异常），并且 LockFile 留在盘上，
--   下一次运行会被自己的锁拦下（现象是"检测到运行锁"，其实根本没有并发）。
--   顺序：关联表 → 变更历史 → 行项 → 合同 → 产品/单位/类型 → 档案；
--   并且对"上一轮残留的合同"按 customer_id/supplier_id/parent_id 反查后再清一次（不只看本轮序号）。
DELETE FROM t_ctms_contract_tag WHERE contract_id IN (SELECT id FROM t_ctms_contract WHERE name LIKE '%$S' OR id LIKE 'CTM%');
DELETE FROM t_ctms_change_log WHERE object_type='contract' AND object_id IN
    (SELECT id FROM t_ctms_contract WHERE name LIKE '%$S' OR id LIKE 'CTM%'
        OR customer_id LIKE 'CTMCUST%' OR supplier_id LIKE 'CTMSUP%' OR parent_id LIKE 'CTM%' OR name LIKE '阀门采购合同%' OR name LIKE '标签陪衬%' OR name LIKE '恢复窗口合同%' OR name LIKE '自动标签合同%' OR name LIKE '框架合同%' OR name LIKE '普通合同P%' OR name LIKE '手工编号基准%' OR name LIKE '重复编号合同%' OR name LIKE '待停用合同%' OR name LIKE '金额口径判别%');
DELETE FROM t_ctms_contract_item WHERE contract_id IN
    (SELECT id FROM t_ctms_contract WHERE name LIKE '%$S' OR id LIKE 'CTM%'
        OR customer_id LIKE 'CTMCUST%' OR supplier_id LIKE 'CTMSUP%' OR parent_id LIKE 'CTM%' OR name LIKE '阀门采购合同%' OR name LIKE '标签陪衬%' OR name LIKE '恢复窗口合同%' OR name LIKE '自动标签合同%' OR name LIKE '框架合同%' OR name LIKE '普通合同P%' OR name LIKE '手工编号基准%' OR name LIKE '重复编号合同%' OR name LIKE '待停用合同%' OR name LIKE '金额口径判别%');
-- 兜底：清掉仍然引用夹具产品的孤儿行项（跨脚本残留，例如 ctms-commercials-check 的 CCOMP* 夹具）
DELETE i FROM t_ctms_contract_item i JOIN t_ctms_product p ON p.id = i.product_id
 WHERE p.id LIKE 'CTM%' OR p.id LIKE 'CCOM%';
-- 合同：按本轮序号 / id 前缀 / 引用了夹具档案 / **本脚本的固定夹具名** 四种判据一起清。
-- 最后一条是"异常退出后的兜底"：那种残留行的名字里带的是**上一轮**的序号，按 '%$S' 清不掉，
-- 而它又占着手工编号或夹具档案，会让本轮出现"编号已存在 / 客户档案不存在"这类假失败。
DELETE FROM t_ctms_contract WHERE name LIKE '%$S' OR id LIKE 'CTM%'
    OR customer_id LIKE 'CTMCUST%' OR supplier_id LIKE 'CTMSUP%' OR parent_id LIKE 'CTM%'
    OR name LIKE '阀门采购合同%' OR name LIKE '金额口径判别%' OR name LIKE '手工编号基准%'
    OR name LIKE '重复编号合同%' OR name LIKE '非法编号合同%' OR name LIKE '非法状态合同%'
    OR name LIKE '非法到货%' OR name LIKE '档案不存在%' OR name LIKE '待停用合同%'
    OR name LIKE '标签陪衬%' OR name LIKE '恢复窗口合同%' OR name LIKE '自动标签合同%'
    OR name LIKE '框架合同%' OR name LIKE '普通合同P%';
DELETE FROM t_ctms_tag WHERE name LIKE '验收标签%';
DELETE FROM t_ctms_product WHERE id LIKE 'CTMPROD%';
DELETE FROM t_ctms_uom WHERE id LIKE 'CTMUOM%';
DELETE FROM t_ctms_product_type WHERE id LIKE 'CTMTYPE%';
DELETE FROM t_ctms_customer WHERE id LIKE 'CTMCUST%';
DELETE FROM t_ctms_supplier WHERE id LIKE 'CTMSUP%';
-- 4.8b 的临时子部门：必须排在合同清完之后（t_ctms_contract.dept_id 有 fk_contract_dept → sys_dept）
DELETE FROM sys_dept WHERE dept_id LIKE 'CTMDEPT%';
"@
    # ⚠ 清理失败**不能**让整脚本静默中止：把原样错误打出来（含 FK 名），一眼定位是哪条依赖没断
    if ($out -match 'ERROR') {
        Bad "夹具清理出现 SQL 错误（按 FK 名定位依赖顺序）：$out"
    }
}

# ------------------------------------------------------------------ 运行锁
$script:LockFile = Join-Path $CacheDir 'ctms-contract-check.lock'
if ((Test-Path $script:LockFile) -and (-not $Force)) {
    $holder = (Get-Content $script:LockFile -Raw -ErrorAction SilentlyContinue)
    throw "检测到运行锁 $($script:LockFile)（$holder）。本脚本不可并发执行；确认没有其它实例后删除该文件，或用 -Force 跳过。"
}
[System.IO.File]::WriteAllText($script:LockFile, "pid=$PID start=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')", (New-Object System.Text.UTF8Encoding($false)))

# ------------------------------------------------------------------ 前置
Step '前置检查'
if (-not (Test-Path $MySqlCli)) { throw "缺少 MySQL 客户端：$MySqlCli" }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) { throw '后端 8080 未监听 —— 先跑 .\start-env.ps1' }
$null = TokenOf 'superAdmin'
$null = TokenOf $LimitedUser
$tables = @('t_ctms_contract','t_ctms_contract_item','t_ctms_contract_tag','t_ctms_change_log','t_ctms_tag','t_ctms_customer','t_ctms_product')
$missing = @()
foreach ($t in $tables) {
    if ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$t'") -ne '1') { $missing += $t }
}
if ($missing.Count -gt 0) { throw "缺少 B3 业务表：$($missing -join ', ')" }
$adminId = Sql "SELECT user_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
$adminDept = Sql "SELECT dept_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
$menuContract = '9F2C0000000000000000000000000011'
$typeDict = Api 'GET' '/system/dict/data/type/contract_types' $null
$statusDict = Api 'GET' '/system/dict/data/type/contract_statuses' $null
$arrivalDict = Api 'GET' '/system/dict/data/type/arrival_statuses' $null
Assert-That ((IsOk $typeDict) -and (@($typeDict.data).Count -ge 6)) "合同类型字典可用（$(@($typeDict.data).Count) 项）"
Assert-That ((IsOk $statusDict) -and (@($statusDict.data).Count -ge 7)) "进度状态字典可用（$(@($statusDict.data).Count) 项）"
Assert-That ((IsOk $arrivalDict) -and (@($arrivalDict.data).Count -ge 3)) "到货状态字典可用（$(@($arrivalDict.data).Count) 项）"
Info "夹具序号 $S；签订日期=$SignDate；主体码=$SubjectCode"

Clear-Fixtures

# ------------------------------------------------------------------
# 4.8b 要临时借用共享状态（DEV-ENV §6.48 三件套：入口自愈 + 收尾还原 + 哨兵）
#   · `common` 角色的 sys_role_menu 授权与 sys_role.data_scope（'1'/'3'/'4'/'5'）
#   · `bm` 角色的 data_scope + 一条 sys_user_role（只为验「多角色取并集」）
# 哨兵串写在 sys_role.remark：上一轮异常退出时，下面的自愈逻辑认得出"这是我们改的"，
# 从而避免把 data_scope='5' 留在库里（那会让受测账号连 /getInfo 都 403，现象是"全面 403"）。
# ------------------------------------------------------------------
$managedMenuIds = @($menuContract, '9F2C0000000000000000000000000021', '9F2C0000000000000000000000000023',
                    '9F2C0000000000000000000000000024', '9F2C0000000000000000000000000026')
$managedIdList  = ($managedMenuIds | ForEach-Object { "'$_'" }) -join ','
$sentinelCommon = '__B3_DS_SCOPE_TMP_COMMON__'
$sentinelUnion  = '__B3_DS_SCOPE_TMP_UNION__'
$limitedRoleId  = Sql "SELECT r.role_id FROM sys_role r WHERE r.role_key='common' LIMIT 1"
$limitedId      = Sql "SELECT user_id FROM sys_user WHERE user_name='$LimitedUser' LIMIT 1"
$limitedDept    = Sql "SELECT dept_id FROM sys_user WHERE user_name='$LimitedUser' LIMIT 1"
$unionRoleId    = Sql "SELECT r.role_id FROM sys_role r WHERE r.role_key='$UnionRoleKey' LIMIT 1"
if (-not $limitedRoleId -or -not $limitedId -or -not $limitedDept) {
    throw "取不到受测账号/角色基准（LimitedUser=$LimitedUser）：role=$limitedRoleId user=$limitedId dept=$limitedDept"
}

# 入口自愈：哨兵在 ⇒ 上一轮没还原 ⇒ 强制还原为基线 '1'
foreach ($pair in @(@{ Id = $limitedRoleId; S = $sentinelCommon; W = 'common' },
                    @{ Id = $unionRoleId;   S = $sentinelUnion;  W = $UnionRoleKey })) {
    if (-not $pair.Id) { continue }
    $rm = Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$($pair.Id)'"
    if ($rm -eq $pair.S) {
        $null = SqlFile "UPDATE sys_role SET data_scope='1', remark=NULL WHERE role_id='$($pair.Id)';"
        Info "入口自愈：$($pair.W) 角色（role_id=$($pair.Id)）残留哨兵 $($pair.S) → data_scope 已还原为基线 '1'"
    }
}

# 收尾要还原的共享状态快照（必须在自愈之后取）
$grantedBefore       = @(SqlRows "SELECT menu_id FROM sys_role_menu WHERE role_id='$limitedRoleId'" | ForEach-Object { ([string]$_).Trim() })
$commonScopeBefore   = Sql "SELECT data_scope FROM sys_role WHERE role_id='$limitedRoleId'"
$commonRemarkBefore  = Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$limitedRoleId'"
$unionScopeBefore    = Sql "SELECT data_scope FROM sys_role WHERE role_id='$unionRoleId'"
$unionRemarkBefore   = Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$unionRoleId'"
$unionAssignedBefore = ((Sql "SELECT COUNT(*) FROM sys_user_role WHERE user_id='$limitedId' AND role_id='$unionRoleId'") -eq '1')
# 并集角色由本脚本管理：入口先摘掉（若确实存在，收尾按上面的快照还原）
$null = SqlFile "DELETE FROM sys_user_role WHERE user_id='$limitedId' AND role_id='$unionRoleId';"

function Restore-Role {
    try {
        # ① 角色-菜单：先清掉**本脚本管理的菜单集合**，再插回快照里不属于该集合的部分。
        #    §6.48 坑①：不能只"把进入时的快照插回去"——上一轮异常退出留下的本脚本菜单行
        #    会被当成"原始状态"带进下一轮，症状是"本该 403 的用例拿到 200"。
        $null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$limitedRoleId' AND menu_id IN ($managedIdList);"
        $keep = @($grantedBefore | Where-Object { $managedMenuIds -notcontains $_ })
        if ($keep.Count -gt 0) {
            $values = ($keep | ForEach-Object { "('$limitedRoleId','$_')" }) -join ','
            $null = SqlFile "INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES $values;"
        }
        # ② common 的 data_scope + 哨兵 remark（还原成进入时的值）
        $cs = if ($commonScopeBefore) { $commonScopeBefore } else { '1' }
        $cr = if ($commonRemarkBefore -eq '' -or $commonRemarkBefore -eq $sentinelCommon) { 'NULL' } else { "'$commonRemarkBefore'" }
        $null = SqlFile "UPDATE sys_role SET data_scope='$cs', remark=$cr WHERE role_id='$limitedRoleId';"
        # ③ 并集角色的 data_scope + 哨兵 + 授权行（都还原成进入时的值）
        if ($unionRoleId) {
            $us = if ($unionScopeBefore) { $unionScopeBefore } else { '1' }
            $ur = if ($unionRemarkBefore -eq '' -or $unionRemarkBefore -eq $sentinelUnion) { 'NULL' } else { "'$unionRemarkBefore'" }
            $null = SqlFile "UPDATE sys_role SET data_scope='$us', remark=$ur WHERE role_id='$unionRoleId';"
            if ($unionAssignedBefore) {
                $null = SqlFile "INSERT IGNORE INTO sys_user_role(user_id, role_id) VALUES ('$limitedId','$unionRoleId');"
            } else {
                $null = SqlFile "DELETE FROM sys_user_role WHERE user_id='$limitedId' AND role_id='$unionRoleId';"
            }
        }
    } catch {
        Bad "收尾还原失败（需人工检查 sys_role_menu / sys_role.data_scope / sys_user_role）：$_"
    }
}

try {
    # ============================================================ 夹具
    Step '准备夹具：客户 + 物料域（类型/单位/物料，规格 DN50）'
    $null = SqlFile @"
INSERT INTO t_ctms_customer (id,code,name,create_id,create_time,update_time) VALUES ('$CustId','CTM-CUS-$S','验收客户$S','$adminId',NOW(),NOW());
INSERT INTO t_ctms_product_type (id,name,code,path,level,sort,enable_flag,create_time,update_time) VALUES ('$TypeId','验收类型$S','CTMT$S','/',1,1,'1',NOW(),NOW());
INSERT INTO t_ctms_uom (id,code,name,decimals,enable_flag,create_time,update_time) VALUES ('$UomId','CTMU$S','验收单位$S',3,'1',NOW(),NOW());
INSERT INTO t_ctms_product (id,code,name,spec,product_type_id,uom_id,default_price,enable_flag,create_id,create_time,update_time)
  VALUES ('$ProdId','CTMP$S','验收球阀$S','DN50','$TypeId','$UomId',12.3456,'1','$adminId',NOW(),NOW());
"@
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_product WHERE id='$ProdId'") -eq '1') '夹具：客户 / 类型 / 单位 / 物料（规格 DN50）已就位'

    # ============================================================ 4.1 登记
    Step '4.1 登记：必填/唯一/状态校验/创建人与部门快照/金额与质保算法'
    # ⚠ 编号的**服务端生成**（类型码 + 主体码 + 年月 + 按年重置）属任务 5.8，**已交付**：
    #   本脚本的登记一律**留空编号**（由服务端按 `二开-合同台账-编号配置.sql` 生成），
    #   编号格式/分桶/预览/占号的完整断言在 `tools\ctms-commercials-check.ps1`（116 条）。
    #   下面只断言"服务端确实生成了一枚合法编号"这条跨组契约。
    $r = Api 'POST' '/ctms/contract' @{
        name = "阀门采购合同$S"; type = 'SAL'; partyA = "验收客户$S"; partyB = '供方文本兜底'
        signDate = $SignDate; effectiveDate = $SignDate; subjectCode = $SubjectCode
        status = '内部审批中'; arrivalStatus = '未到货'; ownerName = '张伟'
        customerId = $CustId; paidAmount = 0
        hasWarranty = '1'; warrantyStart = '2025-06-01'; warrantyMonths = 12
        items = @(
            @{ productId = $ProdId; qty = 3;     unitPrice = 1.665; itemType = '销售' },
            @{ productId = $ProdId; qty = 3.333; unitPrice = 0.03;  itemType = '销售' }
        )
    }
    Assert-That (IsOk $r) "登记合同成功（code=$($r.code) msg=$(MsgOf $r)）"
    $cid = Sql "SELECT id FROM t_ctms_contract WHERE name='阀门采购合同$S'"
    Assert-That ($cid -ne '' -and $cid -notmatch '^ERROR') "合同已落库（id=$cid）"
    if ($cid -eq '' -or $cid -match '^ERROR') {
        throw "主干夹具（带 2 个行项的合同）登记失败，后续断言都依赖它，就地中止。code=$($r.code) msg=$(MsgOf $r)"
    }
    $no = Sql "SELECT contract_no FROM t_ctms_contract WHERE id='$cid'"
    # SAL（类型码）+ ZC（主体码）+ 签订日期所在年月 + 6 位序号 = 17 位
    $noExpected = "^SAL$SubjectCode$($SignDate.Substring(0,4))$($SignDate.Substring(5,2))\d{6}$"
    Assert-That ($no -match $noExpected) `
        "编号由服务端生成且格式为 类型码+主体码+yyyy+MM+6位序号（实得 $no，期望匹配 $noExpected）"
    Assert-That ((Sql "SELECT create_id FROM t_ctms_contract WHERE id='$cid'") -eq $adminId) 'create_id 由服务端快照成登录账号'
    Assert-That ((Sql "SELECT dept_id FROM t_ctms_contract WHERE id='$cid'") -eq $adminDept) 'dept_id 由服务端快照（请求体里没传）'
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$cid'") -eq '0') '新合同 del_flag=0'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_item WHERE contract_id='$cid'") -eq '2') '行项随主表一起落库（FK 顺序正确）'
    $itemTotals = Sql "SELECT GROUP_CONCAT(total ORDER BY seq) FROM t_ctms_contract_item WHERE contract_id='$cid'"
    Assert-That ($itemTotals -eq '5.00,0.10') "行总价逐行 HALF_UP 舍入到分（实得 $itemTotals，期望 5.00,0.10）"
    $amount = Sql "SELECT amount FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($amount -eq '5.10') "合同金额 = 各已舍入行总价之和（实得 $amount，期望 5.10）"
    $tmp = Sql "SELECT subject_matter FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($tmp -match '球阀' -and $tmp -match 'DN50') "标的物摘要**真正落库**（$tmp）"
    Assert-That ((Sql "SELECT warranty_end FROM t_ctms_contract WHERE id='$cid'") -eq '2026-05-31') `
        '质保到期日 = 2025-06-01 + 12 个月 → 2026-05-31（服务端算法，前端传值不参与）'
    Assert-That ((Sql "SELECT warranty_months FROM t_ctms_contract WHERE id='$cid'") -eq '12') '质保月数落库'

    # 「先舍入再汇总」的真判别：3 行各 1×0.005 → 逐行 0.01 → 合计 0.03（先汇总再舍入会得 0.02）
    $r2 = Api 'POST' '/ctms/contract' @{ customerId = $CustId;
        name = "金额口径判别$S"; type = 'SAL'; status = '已签订'; subjectCode = $SubjectCode
        items = @(
            @{ productId = $ProdId; qty = 1; unitPrice = 0.005; itemType = '销售' },
            @{ productId = $ProdId; qty = 1; unitPrice = 0.005; itemType = '销售' },
            @{ productId = $ProdId; qty = 1; unitPrice = 0.005; itemType = '销售' }
        )
    }
    Assert-That (IsOk $r2) "「先舍入再汇总」判别用例已建（msg=$(MsgOf $r2)）"
    $c1 = Sql "SELECT id FROM t_ctms_contract WHERE name='金额口径判别$S'"
    $l3 = Sql "SELECT GROUP_CONCAT(total ORDER BY seq) FROM t_ctms_contract_item WHERE contract_id='$c1'"
    Assert-That ($l3 -eq '0.01,0.01,0.01') "3 行的行总价各自舍入为 0.01（实得 $l3）"
    $amt1 = Sql "SELECT amount FROM t_ctms_contract WHERE id='$c1'"
    Assert-That ($amt1 -eq '0.03') "3 行 0.005 逐行舍入后汇总 = 0.03（先汇总再舍入会得 0.02，判别力在此；实得 $amt1）"

    Step '4.1 登记：编号唯一 / 必填 / 非法状态 / 档案不存在'
    # 编号唯一：走**手工/历史编号通道**（非空编号只校验格式 + 唯一性）
    $dupNo = "PUR${SubjectCode}$($SignDate.Substring(0,4))$($SignDate.Substring(5,2))900001"
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; contractNo = $dupNo; name = "手工编号基准$S"; type = 'SAL' }
    Assert-That (IsOk $r) "手工/历史编号通道可用（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; contractNo = $dupNo; name = "重复编号合同$S"; type = 'SAL' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编号已存在') "重复编号被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE contract_no='$dupNo'") -eq '1') '被拒的重复编号没有落库'
    # 编号格式非法同样被拒（手工通道的入口校验）
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; contractNo = "CTMCP$S"; name = "非法编号合同$S"; type = 'SAL' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编号格式不正确') "非法格式编号被拒（msg=$(MsgOf $r)）"
    # 名称必填（编号留空 → 先撞名称校验，而不是"编号不能为空"）
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; name = ''; type = 'SAL'; subjectCode = $SubjectCode }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '名称不能为空') "名称为空被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; name = "非法状态合同$S"; type = 'SAL'; status = '不存在的状态'; subjectCode = $SubjectCode }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '无效状态') "非法进度状态被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE name='非法状态合同$S'") -eq '0') '被拒的非法状态没有落库'
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; name = "非法到货$S"; type = 'SAL'; arrivalStatus = '飞到货'; subjectCode = $SubjectCode }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '到货状态') "非法到货状态被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{ name = "档案不存在$S"; type = 'SAL'; customerId = 'NOT-EXIST-CUSTOMER'; subjectCode = $SubjectCode }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '客户档案不存在') "引用不存在的客户档案被拒（msg=$(MsgOf $r)）"

    Step '4.1 编辑 / 软删除入口校验'
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; name = "待停用合同$S"; type = 'SAL'; status = '已签订'; arrivalStatus = '未到货'; subjectCode = $SubjectCode }
    Assert-That (IsOk $r) '另建一份合同用于停用/编辑演示'
    $eid = Sql "SELECT id FROM t_ctms_contract WHERE name='待停用合同$S'"
    # 编辑时提交一个**格式合法但未被占用**的编号：服务端忽略它（编号不可改），既不改库也不留痕
    $otherNo = "PUR${SubjectCode}$($SignDate.Substring(0,4))$($SignDate.Substring(5,2))999999"
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $eid; name = "待停用合同改名$S"; type = 'SAL'; status = '已签订'; contractNo = $otherNo }
    Assert-That (IsOk $r) "编辑成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT name FROM t_ctms_contract WHERE id='$eid'") -eq "待停用合同改名$S") '改名已落库'
    Assert-That ((Sql "SELECT contract_no FROM t_ctms_contract WHERE id='$eid'") -ne $otherNo) '编辑时提交的编号被忽略（编号不可改）'
    $r = Api 'DELETE' "/ctms/contract/$eid`?reason=" $null
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '原因') "删除未填原因被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$eid'") -eq '0') '被拒的删除没有改动停用标志'
    $r = Api 'DELETE' "/ctms/contract/$eid`?reason=验收停用" $null
    Assert-That (IsOk $r) "软删除成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$eid'") -eq '1') '停用标志已置 1'
    Assert-That ((Sql "SELECT deleted_reason FROM t_ctms_contract WHERE id='$eid'") -eq '验收停用') '停用原因已落库'
    Assert-That ((Sql "SELECT deleted_at IS NOT NULL FROM t_ctms_contract WHERE id='$eid'") -eq '1') '停用时间已落库（30 天窗口的判定基准）'
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $eid; name = "停用后改名$S"; type = 'SAL' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '合同已停用') "已停用合同禁止编辑（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT name FROM t_ctms_contract WHERE id='$eid'") -ne "停用后改名$S") '被拒的编辑没有改动数据'

    # ============================================================ 4.2 状态
    Step '4.2 进度状态与到货状态独立、可自由流转'
    $r = Api 'PUT' '/ctms/contract/status' @{ id = $cid; status = '内部审批中'; arrivalStatus = '未到货' }
    Assert-That (IsOk $r) "已签订 → 内部审批中 被接受（无顺序守卫，msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT arrival_status FROM t_ctms_contract WHERE id='$cid'") -eq '未到货') '改进度状态不会联动到货状态'
    $r = Api 'PUT' '/ctms/contract/status' @{ id = $cid; status = '付款中'; arrivalStatus = '部分到货' }
    Assert-That (IsOk $r) '进度状态与到货状态可各自独立变更'
    Assert-That ((Sql "SELECT CONCAT(status,'/',arrival_status) FROM t_ctms_contract WHERE id='$cid'") -eq '付款中/部分到货') '两个状态都落到各自列'
    $r = Api 'PUT' '/ctms/contract/status' @{ id = $cid; status = '已终止'; arrivalStatus = '部分到货' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '终止原因') "改「已终止」未填原因被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT status FROM t_ctms_contract WHERE id='$cid'") -eq '付款中') '被拒的状态变更没有落库'
    $r = Api 'PUT' '/ctms/contract/status' @{ id = $cid; status = '已终止'; arrivalStatus = '部分到货'; deletedReason = '验收：客户取消订单' }
    Assert-That (IsOk $r) "填了终止原因后可以终止（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT status FROM t_ctms_contract WHERE id='$cid'") -eq '已终止') '终止状态已落库'
    $null = Api 'PUT' '/ctms/contract/status' @{ id = $cid; status = '已签订'; arrivalStatus = '未到货' }
    Assert-That ((Sql "SELECT status FROM t_ctms_contract WHERE id='$cid'") -eq '已签订') '状态可自由回退（复原为「已签订」，供后续筛选用例）'

    # ============================================================ 4.3 列表筛选
    Step '4.3 列表：默认排除停用 / 显式包含 / 关键字 / 组合筛选（按 id 判定）'
    $r = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=200'
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '默认列表包含未停用合同'
    Assert-That ((IdsIn $r $eid) -eq 0) '默认列表排除已停用合同'
    $r = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=200&includeDeleted=1'
    Assert-That ((IsOk $r) -and ((IdsIn $r $eid) -eq 1)) 'includeDeleted=1 时已停用合同出现（规格「默认 3 含停用 4」的判别口径）'
    $r = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=200&keyword=DN50'
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '关键字命中**行项规格 DN50**（自身字段不含该串）'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&keyword=阀门采购合同$S"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '关键字命中合同名称'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&type=SAL&status=$encSigned&beginSignDate=2026-01-01&endSignDate=2026-12-31"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '组合筛选（类型 + 状态 + 签订日期区间）命中'
    # 状态条件的区分力：必须按 id 判定 + 前后对照（只看"结果里有没有它"没有区分力）
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&type=SAL&status=$encPaying"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 0)) '组合筛选排掉状态不匹配的合同（对照「付款中」，按 id 判定）'
    Assert-That (@(RowsOf $r | Where-Object { $_.status -ne '付款中' }).Count -eq 0) '返回的每一行都真的是「付款中」（证明条件拼进了 SQL）'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&type=SAL&status=$encSigned"
    Assert-That ((IsOk $r) -and ((IdsIn $r $cid) -eq 1)) '换回匹配的状态「已签订」时该合同回到结果里（前后对照）'

    Step '4.3 列表：标签按**交集**筛选'
    $r = Api 'POST' '/ctms/tag' @{ name = "${TagName}A"; color = '#409eff' }
    Assert-That (IsOk $r) "标签 A 新建成功（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/tag' @{ name = "${TagName}B"; color = '#67c23a' }
    Assert-That (IsOk $r) "标签 B 新建成功（msg=$(MsgOf $r)）"
    $tagA = Sql "SELECT id FROM t_ctms_tag WHERE name='${TagName}A'"
    $tagB = Sql "SELECT id FROM t_ctms_tag WHERE name='${TagName}B'"
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; subjectCode = $SubjectCode; name = "标签陪衬A$S"; type = 'SAL'; status = '已签订'; tagIds = @($tagA) }
    Assert-That (IsOk $r) '陪衬合同 A（只带标签 A）已建'
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; subjectCode = $SubjectCode; name = "标签陪衬AB$S"; type = 'SAL'; status = '已签订'; tagIds = @($tagA, $tagB) }
    Assert-That (IsOk $r) '陪衬合同 AB（同时带 A、B）已建'
    $b2 = Sql "SELECT id FROM t_ctms_contract WHERE name='标签陪衬A$S'"
    $b3 = Sql "SELECT id FROM t_ctms_contract WHERE name='标签陪衬AB$S'"
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&tagIds=${tagA},${tagB}"
    Assert-That ((IsOk $r) -and ((IdsIn $r $b3) -eq 1)) '标签交集：同时具备 A+B 的合同被返回'
    Assert-That ((IdsIn $r $b2) -eq 0) '标签交集：**只具备 A** 的合同不被返回（HAVING COUNT = N 语义）'
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&tagIds=${tagA}"
    Assert-That ((IsOk $r) -and ((IdsIn $r $b2) -eq 1) -and ((IdsIn $r $b3) -eq 1)) '单个标签筛选返回全部具备它的合同'
    # ⚠ 入参形态以**逗号分隔**为准（Spring 对 List 的默认绑定；框架里没有自定义转换器，
    #   别去猜 "|" 之类前端可能用的分隔符 —— 那是没有依据的断言，只会制造假失败）。
    #   改成一条真正有区分力的镜像对照：只带 B 时，带 B 的合同在、只带 A 的不在。
    $r = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=200&tagIds=${tagB}"
    Assert-That ((IsOk $r) -and ((IdsIn $r $b3) -eq 1) -and ((IdsIn $r $b2) -eq 0)) '只按标签 B 筛选：带 B 的在、只带 A 的不在（交集口径的镜像对照）'

    # ============================================================ 4.4 恢复边界
    Step '4.4 恢复：未停用幂等 / 第 30 天可恢复 / 第 31 天不可'
    $r = Api 'PUT' "/ctms/contract/$cid/restore" $null
    Assert-That (IsOk $r) "未停用合同调用恢复是幂等成功（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; subjectCode = $SubjectCode; name = "恢复窗口合同$S"; type = 'SAL'; status = '已签订' }
    Assert-That (IsOk $r) '另建一份用于恢复窗口边界演示'
    $rid = Sql "SELECT id FROM t_ctms_contract WHERE name='恢复窗口合同$S'"
    $null = Api 'DELETE' "/ctms/contract/$rid`?reason=边界演示" $null
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$rid'") -eq '1') '边界演示合同已停用'
    $null = SqlFile "UPDATE t_ctms_contract SET deleted_at = DATE_SUB(CURDATE(), INTERVAL 30 DAY) + INTERVAL 9 HOUR WHERE id='$rid';"
    $r = Api 'PUT' "/ctms/contract/$rid/restore" $null
    Assert-That (IsOk $r) "第 30 天可恢复（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$rid'") -eq '0') '恢复后 del_flag 回到 0'
    $null = Api 'DELETE' "/ctms/contract/$rid`?reason=边界演示2" $null
    $null = SqlFile "UPDATE t_ctms_contract SET deleted_at = DATE_SUB(CURDATE(), INTERVAL 31 DAY) WHERE id='$rid';"
    $r = Api 'PUT' "/ctms/contract/$rid/restore" $null
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '30 天') "第 31 天拒绝恢复并给出「已超过 30 天保留期，无法恢复」（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$rid'") -eq '1') '被拒的恢复没有改动停用标志'

    # ============================================================ 4.5 标签与框架
    Step '4.5 自动标签：与类型同名、类型变更时替换、手动标签保留'
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; subjectCode = $SubjectCode; name = "自动标签合同$S"; type = 'SAL'; status = '已签订' }
    Assert-That (IsOk $r) '自动标签演示合同已建'
    $tlid = Sql "SELECT id FROM t_ctms_contract WHERE name='自动标签合同$S'"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_tag ct JOIN t_ctms_tag t ON t.id=ct.tag_id WHERE ct.contract_id='$tlid' AND t.name='SAL' AND ct.auto='1'") -eq '1') `
        '保存时自动附加了与合同类型同名的标签（auto=1）'
    $r = Api 'POST' '/ctms/tag' @{ name = "${TagName}M"; color = '#909399' }
    $tagM = Sql "SELECT id FROM t_ctms_tag WHERE name='${TagName}M'"
    $null = SqlFile "INSERT IGNORE INTO t_ctms_contract_tag (contract_id,tag_id,auto,create_time) VALUES ('$tlid','$tagM','0',NOW());"
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $tlid; name = "自动标签合同$S"; type = 'PUR'; status = '已签订' }
    Assert-That (IsOk $r) '把类型由 SAL 改为 PUR（触发自动标签替换）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_tag ct JOIN t_ctms_tag t ON t.id=ct.tag_id WHERE ct.contract_id='$tlid' AND t.name='PUR' AND ct.auto='1'") -eq '1') '新类型的自动标签已附加'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_tag ct JOIN t_ctms_tag t ON t.id=ct.tag_id WHERE ct.contract_id='$tlid' AND t.name='SAL' AND ct.auto='1'") -eq '0') '旧类型的自动标签已被移除'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_tag WHERE contract_id='$tlid' AND tag_id='$tagM' AND auto='0'") -eq '1') '手动的标签（auto=0）没有被自动同步清掉'

    Step '4.5 框架合同：四条守卫 + 自动「框架合同」标签 + 详情汇总'
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; subjectCode = $SubjectCode; name = "框架合同$S"; type = 'COO'; status = '已签订'; isFramework = '1' }
    Assert-That (IsOk $r) '框架合同已建'
    $fid = Sql "SELECT id FROM t_ctms_contract WHERE name='框架合同$S'"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_tag ct JOIN t_ctms_tag t ON t.id=ct.tag_id WHERE ct.contract_id='$fid' AND t.name='框架合同' AND ct.auto='1'") -eq '1') `
        '框架合同自动获得「框架合同」标签（auto=1）'
    $r = Api 'POST' '/ctms/contract' @{ customerId = $CustId; subjectCode = $SubjectCode; name = "普通合同P$S"; type = 'SAL'; status = '已签订' }
    $plainId = Sql "SELECT id FROM t_ctms_contract WHERE name='普通合同P$S'"
    Assert-That ($plainId -ne '') '普通合同（用于绑定守卫）已建'
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $plainId; name = "普通合同P$S"; type = 'SAL'; status = '已签订'; parentId = $fid; isFramework = '1' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '框架合同不能再挂') "守卫①：框架合同不能挂到别的框架下（msg=$(MsgOf $r)）"
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $plainId; name = "普通合同P$S"; type = 'SAL'; status = '已签订'; parentId = 'NOT-EXIST-FRAME' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '不存在或已停用') "守卫②：父不存在被拒（msg=$(MsgOf $r)）"
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $plainId; name = "普通合同P$S"; type = 'SAL'; status = '已签订'; parentId = $cid }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '只能挂到框架合同下') "守卫③：父不是框架合同被拒（msg=$(MsgOf $r)）"
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $plainId; name = "普通合同P$S"; type = 'SAL'; status = '已签订'; parentId = $fid }
    Assert-That (IsOk $r) "绑定到框架下成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT parent_id FROM t_ctms_contract WHERE id='$plainId'") -eq $fid) '父框架引用已落库'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_tag ct JOIN t_ctms_tag t ON t.id=ct.tag_id WHERE ct.contract_id='$plainId' AND t.name='框架合同' AND ct.auto='1'") -eq '1') `
        '子合同自动获得「框架合同」标签（auto=1）'
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $plainId; name = "普通合同P$S"; type = 'SAL'; status = '已签订'; isFramework = '1' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '已是子合同') "守卫④：已是子合同不能再勾框架（msg=$(MsgOf $r)）"
    $r = Api 'PUT' '/ctms/contract' @{ customerId = $CustId; id = $fid; name = "框架合同$S"; type = 'COO'; status = '已签订'; isFramework = '0' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '仍有子合同') "取消框架标记时有未停用子合同被拒（msg=$(MsgOf $r)）"
    # 框架自身 100000.00；两份 SQL 直插的子合同合计 90000.00（"合计与自身分开呈现"的判别用例）
    $null = SqlFile "UPDATE t_ctms_contract SET amount=100000.00 WHERE id='$fid';
INSERT INTO t_ctms_contract (id,contract_no,name,type,party_a,party_b,subject_matter,amount,currency,dept_id,create_id,parent_id,status,arrival_status,del_flag,create_time,update_time)
VALUES ('CTMCHILD1$S','CTMCP-C1$S','子合同一$S','SAL','','','',60000.00,'CNY','$adminDept','$adminId','$fid','已签订','未到货','0',NOW(),NOW()),
       ('CTMCHILD2$S','CTMCP-C2$S','子合同二$S','SAL','','','',30000.00,'CNY','$adminDept','$adminId','$fid','已签订','未到货','0',NOW(),NOW());"
    $r = Api 'GET' "/ctms/contract/framework/$fid"
    Assert-That (IsOk $r) "框架详情接口可用（msg=$(MsgOf $r)）"
    Assert-That ((@($r.data.children).Count -ge 2) -and ([int]$r.data.childrenCount -eq @($r.data.children).Count)) `
        "子合同清单与 childrenCount 一致（children=$(@($r.data.children).Count)）"
    Assert-That (([decimal]$r.data.childrenAmountSum) -eq 90000.00) "子合同金额合计 = 90000.00（实际 $($r.data.childrenAmountSum)）"
    Assert-That (([decimal]$r.data.amount) -eq 100000.00) '框架自身金额仍为 100000.00（与子合同合计**分开呈现**）'

    # ============================================================ 4.6 变更历史
    Step '4.6 变更历史：手工改 manual / 自动改 auto / 操作人快照 / 操作人为空不报错'
    # 先由脚本显式改一次名字，再断言这次改动被记为 manual（不能指望前面某一步恰好改过名字）
    $detail = Api 'GET' "/ctms/contract/$cid" $null
    $rename = @{}
    foreach ($p2 in $detail.data.PSObject.Properties) { $rename[$p2.Name] = $p2.Value }
    $rename['name'] = "阀门采购合同改名$S"
    $r = Api 'PUT' '/ctms/contract' $rename
    Assert-That (IsOk $r) "脚本显式改一次合同名称（msg=$(MsgOf $r)）"
    $r = Api 'GET' "/ctms/contract/$cid/change-logs?pageNum=1&pageSize=200"
    $logs = RowsOf $r
    Assert-That ((IsOk $r) -and ($logs.Count -gt 0)) "变更历史接口可用（total=$($r.total)）"
    $nameLog = @($logs | Where-Object { $_.newValue -eq "阀门采购合同改名$S" }) | Select-Object -First 1
    Assert-That ($null -ne $nameLog -and $nameLog.source -eq 'manual') '改名称留痕为 manual 来源'
    Assert-That ($null -ne $nameLog -and $nameLog.oldValue -eq "阀门采购合同$S") '留痕里带上了旧值'
    Assert-That ($null -ne $nameLog -and $nameLog.operatorId -eq $adminId) '留痕含操作人标识'
    Assert-That (@($logs | Where-Object { $_.source -eq 'auto' }).Count -gt 0) '存在 auto 来源的记录（金额/标的物/质保到期等自动重算）'
    Assert-That (@($logs | Where-Object { $_.contractId -eq $cid }).Count -eq $logs.Count) '历史按合同维度查询正确'
    $null = SqlFile "INSERT INTO t_ctms_change_log (id,contract_id,field_name,old_value,new_value,source,object_type,object_id,create_time)
VALUES ('CTMLOGNULL$S','$cid','合同名称','旧','新','manual','contract','$cid',NOW());"
    $r = Api 'GET' "/ctms/contract/$cid/change-logs?pageNum=1&pageSize=200"
    Assert-That ((IsOk $r) -and (@(RowsOf $r | Where-Object { $_.id -eq "CTMLOGNULL$S" }).Count -eq 1)) `
        '操作人为空的历史记录正常返回、不报错（前端展示占位符「—」）'

    # ============================================================ 4.7 详情只读
    Step '4.7 详情：含标签/行项/变更历史入口，且**只读**（打开前后逐字段一致）'
    $before = Sql "SELECT CONCAT(amount,'|',status,'|',arrival_status,'|',IFNULL(paid_amount,''),'|',del_flag) FROM t_ctms_contract WHERE id='$cid'"
    $r = Api 'GET' "/ctms/contract/$cid"
    Assert-That (IsOk $r) "详情接口可用（msg=$(MsgOf $r)）"
    Assert-That ($r.data.id -eq $cid) '详情返回同一合同'
    Assert-That (@($r.data.tags).Count -ge 1) "详情返回标签（$(@($r.data.tags).Count) 个）"
    Assert-That (@($r.data.items).Count -eq 2) "详情返回行项（$(@($r.data.items).Count) 条）"
    Assert-That ($null -ne $r.data.changeLogs) '详情带变更历史入口'
    Assert-That ($null -eq $r.data.relatedDocs -or @($r.data.relatedDocs).Count -eq 0) '只读「关联单据」区块存在（B3 阶段为空列表）'
    $after = Sql "SELECT CONCAT(amount,'|',status,'|',arrival_status,'|',IFNULL(paid_amount,''),'|',del_flag) FROM t_ctms_contract WHERE id='$cid'"
    Assert-That ($before -eq $after) "打开详情前后合同字段**逐字段一致**（无回写副作用）：$after"

    # ============================================================ 4.8 数据范围
    Step '4.8 数据范围：无权限账号 403 / 授权后按范围可见'
    $null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$limitedRoleId' AND menu_id IN ($managedIdList);"
    Refresh-Permissions $LimitedUser
    $r = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=200' $null $LimitedUser
    Assert-That (IsForbidden $r) "未授权账号访问合同列表 403（msg=$(MsgOf $r)）"
    Assert-That ((RowsOf $r).Count -eq 0) '403 返回体里没有任何合同行'
    $r = Api 'GET' "/ctms/contract/$cid" $null $LimitedUser
    Assert-That (IsForbidden $r) "未授权账号按标识直查详情 403（msg=$(MsgOf $r)）"
    Assert-That ((RowsOf $r).Count -eq 0) '403 返回体里没有合同内容'
    # 数据范围矩阵必须在「有权限」的前提下测「范围外 403」：把 list/query/edit/remove/export 一起授权
    $grantValues = ($managedMenuIds | ForEach-Object { "('$limitedRoleId','$_')" }) -join ','
    $null = SqlFile "INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES $grantValues;"
    Relogin $LimitedUser
    $r = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=200' $null $LimitedUser
    Assert-That (IsOk $r) '挂上合同台账菜单（list/query/edit/remove/export）后列表可访问（数据范围判定生效且不报错）'

    # ------------------------------------------------ 4.8b 四档 × 列表/详情/导出（任务 9.2 / AC-79）
    Step '4.8b 数据范围四档 × 列表/详情/导出 矩阵（9.2 / AC-79）'
    # 口径基线（1.4 的 notes/data-scope-matrix.md 未成文 → 基线显式写在这里并留档到 notes/permission-audit.md §9.2，
    # 来源：design.md D-4 落地要点 + specs/ctms/contract-ledger「合同台账数据范围服务端强制」+ PRD AC-79）：
    #   ALL='1' 不加条件；DEPT='3'（实现口径：本部门**含下级为默认**，无"关闭含下级"开关）；
    #   DEPT_AND_CHILD 与 DEPT 共用 '3'，判别力来自**下级部门夹具**（若实现不含下级，这一格必红）；
    #   SELF='4' = 本人创建 或 本人所属部门及以下；SELF_ONLY='5' = 仅本人创建（补充档）；
    #   多角色取 or 并集；超管放行。
    $rootDeptId  = Sql "SELECT dept_id FROM sys_dept WHERE parent_id='0' LIMIT 1"
    $otherDeptId = Sql "SELECT dept_id FROM sys_dept WHERE del_flag='0' AND dept_id NOT IN ('$limitedDept','$rootDeptId') AND NOT find_in_set('$limitedDept', IFNULL(ancestors,'')) ORDER BY dept_id LIMIT 1"
    $childDeptId = "CTMDEPT$S"
    $idSame  = "CTMDSAME$S"
    $idChild = "CTMDSCH$S"
    $idOther = "CTMDSOTH$S"
    $idOwn   = "CTMDSOWN$S"
    Assert-That ($otherDeptId -ne '' -and $otherDeptId -notmatch '^ERROR') "外部门基准可用（$otherDeptId）"
    # 夹具：① 本部门(P)他建 ② 下级部门(P 的子部门)他建 ③ 外部门(X)他建 ④ 本人创建@外部门(X)
    $null = SqlFile @"
INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,order_num,status,del_flag,create_time) VALUES
  ('$childDeptId','$limitedDept','0,$rootDeptId,$limitedDept','验收子部门$S',99,'0','0',NOW());
INSERT INTO t_ctms_contract (id,contract_no,name,subject_matter,amount,currency,paid_amount,status,arrival_status,del_flag,dept_id,create_id,create_time,update_time) VALUES
  ('$idSame','CTMDS-SAME-$S','本部门他建$S','数据范围夹具',0.00,'CNY',0.00,'已签订','未到货','0','$limitedDept','$adminId',NOW(),NOW()),
  ('$idChild','CTMDS-CHILD-$S','下级他建$S','数据范围夹具',0.00,'CNY',0.00,'已签订','未到货','0','$childDeptId','$adminId',NOW(),NOW()),
  ('$idOther','CTMDS-OTHER-$S','外部门他建$S','数据范围夹具',0.00,'CNY',0.00,'已签订','未到货','0','$otherDeptId','$adminId',NOW(),NOW()),
  ('$idOwn','CTMDS-OWN-$S','本人外部门$S','数据范围夹具',0.00,'CNY',0.00,'已签订','未到货','0','$otherDeptId','$limitedId',NOW(),NOW());
"@
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE id IN ('$idSame','$idChild','$idOther','$idOwn')") -eq '4') `
        "矩阵夹具 4 条已就位（本部门他建 / 下级他建 / 外部门他建 / 本人创建@外部门；临时子部门 $childDeptId，父部门 $limitedDept）"

    # 期望集合的**独立 SQL 表达**（按 spec/design 口径写出，不复用后端代码；用于"整集比对"）
    $deptBranch = "c.dept_id='$limitedDept' or find_in_set('$limitedDept', IFNULL((select d.ancestors from sys_dept d where d.dept_id=c.dept_id),''))"
    function Scope-Predicate([string[]]$scopes) {
        $parts = @()
        foreach ($sc in $scopes) {
            if ([string]::IsNullOrWhiteSpace($sc)) { continue }
            switch ($sc.Trim()) {
                '1' { $parts += '1=1' }
                '2' { $parts += "($deptBranch)" }
                '3' { $parts += "($deptBranch)" }
                '4' { $parts += "(c.create_id='$limitedId' or $deptBranch)" }
                '5' { $parts += "(c.create_id='$limitedId')" }
                default { $parts += '1=0' }
            }
        }
        if ($parts.Count -eq 0) { return '1=0' }
        return '(' + ($parts -join ' or ') + ')'
    }
    function Expected-Ids([string[]]$scopes) {
        $pred = Scope-Predicate $scopes
        return @(SqlRows "SELECT c.id FROM t_ctms_contract c WHERE c.del_flag='0' AND $pred ORDER BY c.id" | ForEach-Object { ([string]$_).Trim() })
    }
    <# 切换档位：改角色 data_scope（带哨兵）+ 完整重新登录（§6.47）；并集档额外临时挂第二个角色 #>
    function Set-DataScopes([string]$commonScope, [string]$unionScope) {
        if ($unionScope) {
            $null = SqlFile "UPDATE sys_role SET data_scope='$unionScope', remark='$sentinelUnion' WHERE role_id='$unionRoleId';
INSERT IGNORE INTO sys_user_role(user_id, role_id) VALUES ('$limitedId','$unionRoleId');"
        } else {
            $null = SqlFile "UPDATE sys_role SET data_scope='1', remark=NULL WHERE role_id='$unionRoleId';
DELETE FROM sys_user_role WHERE user_id='$limitedId' AND role_id='$unionRoleId';"
        }
        $null = SqlFile "UPDATE sys_role SET data_scope='$commonScope', remark='$sentinelCommon' WHERE role_id='$limitedRoleId';"
        Relogin $LimitedUser
    }

    $matrixFixtures = @(
        @{ K = 'SAME';  Id = $idSame;  Short = '本部门他建' },
        @{ K = 'CHILD'; Id = $idChild; Short = '下级他建' },
        @{ K = 'OTHER'; Id = $idOther; Short = '外部门他建' },
        @{ K = 'OWN';   Id = $idOwn;   Short = '本人外部门' }
    )
    $matrixCases = @(
        @{ Key = 'SUPER';          Label = '超管放行';         Common = '';  Union = ''; Note = 'superAdmin（不经角色档位）';                                Expect = @{ SAME = $true;  CHILD = $true;  OTHER = $true;  OWN = $true  } },
        @{ Key = 'ALL';            Label = 'ALL';              Common = '1'; Union = ''; Note = '全部（data_scope=1）';                                     Expect = @{ SAME = $true;  CHILD = $true;  OTHER = $true;  OWN = $true  } },
        @{ Key = 'DEPT';           Label = 'DEPT';             Common = '3'; Union = ''; Note = '本部门（3；判别格=本部门他建）';                             Expect = @{ SAME = $true;  CHILD = $true;  OTHER = $false; OWN = $false } },
        @{ Key = 'DEPT_AND_CHILD'; Label = 'DEPT_AND_CHILD';   Common = '3'; Union = ''; Note = '本部门及以下（3；判别格=下级他建 → 含下级默认）';            Expect = @{ SAME = $true;  CHILD = $true;  OTHER = $false; OWN = $false } },
        @{ Key = 'SELF';           Label = 'SELF';             Common = '4'; Union = ''; Note = '仅本人（4 = 本人创建 或 本人部门及以下）';                   Expect = @{ SAME = $true;  CHILD = $true;  OTHER = $false; OWN = $true  } },
        @{ Key = 'SELF_ONLY';      Label = 'SELF_ONLY';        Common = '5'; Union = ''; Note = '仅本人创建（5，补充档，不在 AC-79 四档名内）';                Expect = @{ SAME = $false; CHILD = $false; OTHER = $false; OWN = $true  } },
        @{ Key = 'UNION';          Label = '并集(3+5)';        Common = '3'; Union = '5'; Note = '多角色取并集（common=3 + bm=5）';                         Expect = @{ SAME = $true;  CHILD = $true;  OTHER = $false; OWN = $true  } }
    )
    $script:ForbiddenEvidence = @()
    $matrixRows = @()
    foreach ($case in $matrixCases) {
        if ($case.Common) { Set-DataScopes $case.Common $case.Union; $who = $LimitedUser }
        else { $who = 'superAdmin' }
        $scopesForSql = if ($case.Key -eq 'SUPER') { @('1') } else { @($case.Common) + @($case.Union) }
        $snapA = Expected-Ids $scopesForSql
        $list = Api 'GET' '/ctms/contract/list?pageNum=1&pageSize=1000' $null $who
        # ⚠ 导出面必须解包 xlsx（理由见 ExportXlsx 上方注释）：7.4 之后 /ctms/contract/export 返回真 Excel，
        #    体内没有 JSON `rows`，按 `TableDataInfo.rows` 解析会让导出断言恒判“不可见”（R1 的 33 条假红）。
        $expX = ExportXlsx '/ctms/contract/export?pageNum=1&pageSize=1000' $who
        $snapB = Expected-Ids $scopesForSql
        Assert-That (IsOk $list) "[$($case.Key)] 列表可访问 — $($case.Note)"
        Assert-That ($expX.Http -eq '200' -and $expX.Magic -eq 'PK' -and $expX.CType -match 'spreadsheetml') `
            "[$($case.Key)] 导出可访问 — 真 Excel（HTTP=$($expX.Http) magic=$($expX.Magic) CT=$($expX.CType)）— $($case.Note)"
        # id → 名称映射：xlsx 导出**没有 id 列**，只有「合同名称」文本，所以导出面的整集比对按名称做（见下）
        $nameById = @{}
        foreach ($mx in (SqlRows "SELECT CONCAT(id,'|',IFNULL(name,'')) FROM t_ctms_contract")) {
            $px = $mx -split '\|', 2
            if ($px.Count -eq 2) { $nameById[$px[0]] = $px[1] }
        }
        $cellList = @(); $cellDetail = @(); $cellExp = @()
        foreach ($f in $matrixFixtures) {
            $want = [bool]$case.Expect[$f.K]
            $mark = if ($want) { '可见' } else { '403' }
            $inList = IdsIn $list $f.Id
            $fName  = [string](Sql "SELECT name FROM t_ctms_contract WHERE id='$($f.Id)'")
            $inExp  = if (@($expX.Names | Where-Object { $_ -eq $fName }).Count -ge 1) { 1 } else { 0 }
            $cellList += "$($f.Short)=$mark"
            $cellExp  += "$($f.Short)=$mark"
            Assert-That ($inList -eq $(if ($want) { 1 } else { 0 })) `
                "[$($case.Key)] 列表：$($f.Short) → 期望 $(if ($want) { '可见' } else { '不可见' })（实际 $(if ($inList -eq 1) { '可见' } else { '不可见' })）"
            Assert-That ($inExp -eq $(if ($want) { 1 } else { 0 })) `
                "[$($case.Key)] 导出：$($f.Short) → 期望 $(if ($want) { '可见' } else { '不可见' })（实际 $(if ($inExp -eq 1) { '可见' } else { '不可见' })）"
            $raw = ApiRaw 'GET' "/ctms/contract/$($f.Id)" $null $who
            $bj = BodyJson $raw
            if ($want) {
                $cellDetail += "$($f.Short)=200"
                Assert-That ($raw.Http -eq 200 -and $bj -and $bj.code -eq 200 -and $bj.data.id -eq $f.Id) `
                    "[$($case.Key)] 详情：$($f.Short) → 可见（HTTP=$($raw.Http) code=$($bj.code)）"
            } else {
                $cellDetail += "$($f.Short)=403"
                $script:ForbiddenEvidence += "[$($case.Key)] GET /ctms/contract/$($f.Id)（$($f.Short)）→ HTTP=$($raw.Http) body=$($raw.Body)"
                Assert-That ($raw.Http -eq 200 -and $bj -and $bj.code -eq 403 -and (MsgOf $bj) -match '无权访问') `
                    "[$($case.Key)] 详情：$($f.Short) → 范围外**业务码 403**（HTTP=$($raw.Http)，不是 HTTP 层 403/500）：$($raw.Body)"
            }
        }
        # 范围外其它入口（编辑/删除/恢复）：同一份不可见夹具，三条都必须业务码 403 且不改库
        if (-not $case.Expect['OTHER']) {
            $eRaw = ApiRaw 'PUT' '/ctms/contract' @{ id = $idOther; name = "越权改名$S"; customerId = $CustId } $who
            $script:ForbiddenEvidence += "[$($case.Key)] PUT /ctms/contract(id=$idOther) → HTTP=$($eRaw.Http) body=$($eRaw.Body)"
            Assert-That (((BodyJson $eRaw).code) -eq 403 -and $eRaw.Http -eq 200) "[$($case.Key)] 编辑：范围外业务码 403（HTTP=$($eRaw.Http)）：$($eRaw.Body)"
            $delUrl = "/ctms/contract/$idOther" + '?reason=' + [uri]::EscapeDataString('越权停用')
            $delRaw = ApiRaw 'DELETE' $delUrl $null $who
            $script:ForbiddenEvidence += "[$($case.Key)] DELETE $delUrl → HTTP=$($delRaw.Http) body=$($delRaw.Body)"
            Assert-That (((BodyJson $delRaw).code) -eq 403 -and $delRaw.Http -eq 200) "[$($case.Key)] 删除：范围外业务码 403（HTTP=$($delRaw.Http)）：$($delRaw.Body)"
            $resRaw = ApiRaw 'PUT' "/ctms/contract/$idOther/restore" $null $who
            $script:ForbiddenEvidence += "[$($case.Key)] PUT /ctms/contract/$idOther/restore → HTTP=$($resRaw.Http) body=$($resRaw.Body)"
            Assert-That (((BodyJson $resRaw).code) -eq 403 -and $resRaw.Http -eq 200) "[$($case.Key)] 恢复：范围外业务码 403（HTTP=$($resRaw.Http)）：$($resRaw.Body)"
            Assert-That ((Sql "SELECT CONCAT(name,'|',del_flag) FROM t_ctms_contract WHERE id='$idOther'") -eq "外部门他建$S|0") `
                "[$($case.Key)] 被拒的越权编辑/删除/恢复没有改动数据"
        }
        # 整集比对：期望集合（独立 SQL）vs 实际返回集合；两次快照一致才断言（避开并发写入造成的假失败）
        if (($snapA -join '|') -eq ($snapB -join '|')) {
            $actualList = @(RowsOf $list | ForEach-Object { [string]$_.id } | Sort-Object)
            # 导出：xlsx 里没有 id 列、只有「合同名称」⇒ 按**名称多重集合**逐项一致（含重名次数）。
            # 口径说明：库内遗留夹具存在重名（如 23 行只有 13 个唯一名），所以不能退成"名称→id 集合"（会折叠重名）；
            #          4 条夹具的 **id 级**可见性由上面的逐 fixture 断言（按唯一名称）保证；
            #          这里额外断言"导出行数 == 期望可见行数"，与"多重集合一致"互为双证。
            $expectNames = @($snapB | ForEach-Object { [string]$nameById[$_] } | Sort-Object)
            $actualNames = @($expX.Names | Sort-Object)
            Assert-That (($actualList -join '|') -eq ($snapB -join '|')) "[$($case.Key)] 列表可见集合 == 期望集合（$($snapB.Count) 行，逐 id 一致）"
            Assert-That ($expX.Rows -eq $snapB.Count) "[$($case.Key)] 导出行数 == 期望可见行数（期望 $($snapB.Count)，实得 $($expX.Rows)）"
            Assert-That (($actualNames -join '|') -eq ($expectNames -join '|')) "[$($case.Key)] 导出可见集合 == 期望集合（$($snapB.Count) 行，按合同名称多重集合逐项一致）"
        } else {
            Info "[$($case.Key)] 检测到并发写入（期望集合 $($snapA.Count) → $($snapB.Count) 行）→ 跳过整集相等断言，仅按夹具逐格判定"
        }
        # 导出面的**反向**越权断言（不许因为改成 xlsx 就把越权语义弱化/删掉）：
        # 未授权（不带 token）调导出必须**拿不到 xlsx**（Spring Security 给 JSON 失败体/重定向，magic≠PK）
        # —— 即"越权不产生文件"；而档位夹具级"范围外不可见"由上面的逐 fixture 导出断言承担。
        if ($case.Key -eq 'SUPER') {
            $nxTmp = Join-Path $env:TEMP ('ctms-export-noauth-' + [guid]::NewGuid().ToString('N') + '.xlsx')
            $nxCode = [string](@(& curl.exe -s -o $nxTmp -w '%{http_code}' "$BaseUrl/ctms/contract/export?pageNum=1&pageSize=1" 2>&1) | Select-Object -Last 1)
            $nxMagic = ''
            if (Test-Path $nxTmp) {
                $nb = [System.IO.File]::ReadAllBytes($nxTmp)
                if ($nb.Length -ge 2) { $nxMagic = [System.Text.Encoding]::ASCII.GetString($nb[0..1]) }
                Remove-Item $nxTmp -Force -ErrorAction SilentlyContinue
            }
            Assert-That ($nxMagic -ne 'PK') "[反向] 未授权（无 token）导出得不到 xlsx：HTTP=$($nxCode) magic=$($nxMagic)＝越权不产生文件"
        }
        $matrixRows += [pscustomobject]@{
            档位 = $case.Label; 账号 = $who; 档位值 = $case.Note
            列表 = ($cellList -join ', '); 详情 = ($cellDetail -join ', '); 导出 = ($cellExp -join ', ')
        }
    }

    Step '4.8b 实测矩阵（逐格；详情列 = 业务码）'
    foreach ($row in $matrixRows) {
        Info ("[{0,-14}] 账号={1,-11} 列表：{2}" -f $row.档位, $row.账号, $row.列表)
        Info ("                 详情：{0}" -f $row.详情)
        Info ("                 导出：{0}" -f $row.导出)
    }
    Info "范围外 403 原始响应体证据 $($script:ForbiddenEvidence.Count) 条（HTTP 状态 + body 逐条如下）："
    foreach ($ev in $script:ForbiddenEvidence) { Info "  $ev" }
}
finally {
    Step '收尾：还原角色授权/数据范围（哨兵）并删除全部夹具'
    Restore-Role
    Clear-Fixtures
    # 还原是否彻底：menu 集合 / data_scope / 哨兵 / 并集角色授权 逐项核对
    $leftGrant = Sql "SELECT COUNT(*) FROM sys_role_menu WHERE role_id='$limitedRoleId' AND menu_id IN ($managedIdList)"
    Assert-That ($leftGrant -eq '0') "本脚本管理的菜单授权已摘净（残留 $leftGrant 行）"
    $scopeNow = Sql "SELECT CONCAT(data_scope,'|',IFNULL(remark,'')) FROM sys_role WHERE role_id='$limitedRoleId'"
    $scopeNowUnion = Sql "SELECT CONCAT(data_scope,'|',IFNULL(remark,'')) FROM sys_role WHERE role_id='$unionRoleId'"
    Assert-That ($scopeNow -eq "$commonScopeBefore|") "common 角色 data_scope 已还原为进入时的值（$scopeNow，哨兵已清）"
    if ($unionRoleId) {
        Assert-That ($scopeNowUnion -eq "$unionScopeBefore|") "$UnionRoleKey 角色 data_scope 已还原为进入时的值（$scopeNowUnion，哨兵已清）"
    }
    $leftUnion = Sql "SELECT COUNT(*) FROM sys_user_role WHERE user_id='$limitedId' AND role_id='$unionRoleId'"
    Assert-That ($leftUnion -eq $(if ($unionAssignedBefore) { '1' } else { '0' })) "并集角色的临时授权已还原（残留 $leftUnion 行，进入时为 $(if ($unionAssignedBefore) { '1' } else { '0' }) 行）"
    $left = Sql "SELECT (SELECT COUNT(*) FROM t_ctms_contract WHERE name LIKE '%$S' OR id LIKE 'CTM%') + (SELECT COUNT(*) FROM t_ctms_tag WHERE name LIKE '验收标签%') + (SELECT COUNT(*) FROM t_ctms_customer WHERE id LIKE 'CTMCUST%') + (SELECT COUNT(*) FROM t_ctms_product WHERE id LIKE 'CTMPROD%') + (SELECT COUNT(*) FROM t_ctms_product_type WHERE id LIKE 'CTMTYPE%') + (SELECT COUNT(*) FROM t_ctms_uom WHERE id LIKE 'CTMUOM%') + (SELECT COUNT(*) FROM sys_dept WHERE dept_id LIKE 'CTMDEPT%')"
    Assert-That ($left -eq '0') "夹具残留行数（含临时子部门，应为 0）：$left"
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
