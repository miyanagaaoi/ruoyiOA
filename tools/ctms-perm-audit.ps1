<#
================================================================================
 ctms-perm-audit.ps1 —— 9.1 权限点双向核对（sys_menu / 菜单 SQL ↔ 后端 @PreAuthorize）
--------------------------------------------------------------------------------
 判定口径（真源：openspec/changes/oa-contract-ledger/tasks.md 9.1；口径数字见
 notes/attachment-notes.md §4）：**26** 个 `ctms:*` 权限点（第 6 组新增
 `ctms:attachment:list`），本批菜单 **27** 行（1 目录 + 4 菜单 + 22 按钮）。

   集合 A（菜单侧）= SELECT perms FROM sys_menu WHERE perms LIKE 'ctms:%'
   集合 B（后端侧）= grep -rho "ctms:[a-z-]*:[a-z-]*" ruoyi-ctms/src/main/java

 A 与 B 必须**完全相等**，且**两个方向的差异都要打印**：
   · 菜单有、后端无（有菜单无权限 → 按钮点了 403 或压根没后端门禁）
   · 后端有、菜单无（有权限无菜单 → 权限点无法授予，真源缺行）
 差异清单为空 + 口径数字为 26/27 才算通过（退出码 0）；否则退出码 1。

 本脚本**只读**：不写任何 .java、不执行任何 DDL/DML（不 INSERT/UPDATE/DELETE sys_menu）。
 发现差异时只报告，不在脚本里"顺手改"实现或 SQL —— 修法属任务负责人的决定。

 用法（在仓库根目录）：
   cd F:\dsh\ruoyiOA
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-perm-audit.ps1 -VerboseUsage

 前置：MySQL 已启动（便携版 env\mysql），rad_oa 已执行 sql\二开-合同台账-菜单.sql。
 说明：脚本自带受控自证（hyphen 正则自检 + "注释命中不得冒充注解"自检），
       所以"正则漏掉 ctms:contract-item:*"这类失效会被立刻抓住。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = '',
    [string]$MySqlCli = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$Database = 'rad_oa',
    [string]$JavaRel  = 'ruoyi-vue-oa-master\ruoyi-ctms\src\main\java',
    [string]$MenuRel  = 'ruoyi-vue-oa-master\sql\二开-合同台账-菜单.sql',
    [switch]$VerboseUsage
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Split-Path -Parent $PSScriptRoot }
$JavaRoot = Join-Path $RepoRoot $JavaRel
$MenuSql  = Join-Path $RepoRoot $MenuRel

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

# ------------------------------------------------------------------ 只读 SQL 辅助

function SqlRows([string]$q) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $MySqlCli '--host=127.0.0.1' '--user=root' '-D' $Database `
                        '--batch' '--skip-column-names' '--default-character-set=utf8mb4' '-e' $q 2>&1
    $ErrorActionPreference = $prev
    return @($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}

# ------------------------------------------------------------------ 0. 前置检查

Step '0. 前置（只读核对：本脚本不执行任何 DDL/DML）'
Info "仓库根：$RepoRoot"
Info "菜单 SQL：$MenuRel"
Info "后端源码：$JavaRel"
Info "数据库  ：$Database@127.0.0.1（root 空口令）"

Assert-That (Test-Path $MySqlCli)  "mysql 客户端存在：$MySqlCli"
Assert-That (Test-Path $MenuSql)   "菜单 SQL 真源存在：$MenuRel"
Assert-That (Test-Path $JavaRoot)  "后端源码目录存在：$JavaRel"
if (-not (Test-Path $MySqlCli) -or -not (Test-Path $MenuSql) -or -not (Test-Path $JavaRoot)) {
    Write-Host ''
    Write-Host "权限点核对未通过（前置缺失，$script:Pass 通过 / $script:Fail 失败）" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------ 1. 受控自证（正则与口径）

Step '1. 受控自证：正则必须能匹配含连字符的权限点，且注释命中不得冒充注解'
$PermPattern = 'ctms:[a-z-]+:[a-z-]+'
$fixture = @(
    '    @PreAuthorize("@ss.hasPermi(''ctms:contract-item:add'')")',
    '    @PreAuthorize("@ss.hasPermi(''ctms:attachment:list'')")',
    '    @PreAuthorize("@ss.hasPermi(''ctms:contract:edit'') and @ss.hasPermi(''ctms:attachment:list'')")'
)
$fixturePerms = @()
foreach ($l in $fixture) { foreach ($m in [regex]::Matches($l, $PermPattern)) { $fixturePerms += $m.Value } }
$fixturePerms = @($fixturePerms | Sort-Object -Unique)
<# 断言用集合包含关系而不是拼接字符串：PowerShell 的 Sort-Object 是文化序（会忽略连字符），
   用 join 比对会把"正确的集合"误判成失败（本脚本首轮就踩了，见 permission-audit.md §5）。 #>
$fixtureOk = ($fixturePerms.Count -eq 3) `
    -and ($fixturePerms -contains 'ctms:contract-item:add') `
    -and ($fixturePerms -contains 'ctms:attachment:list') `
    -and ($fixturePerms -contains 'ctms:contract:edit')
Assert-That $fixtureOk "hyphen 自检：'$PermPattern' 在夹具上命中 3 个且含 ctms:contract-item:add（实际：$($fixturePerms -join ' | ')）"

# ------------------------------------------------------------------ 2. 菜单侧：SQL 文件 + 真库

Step '2. 菜单侧集合 A：菜单 SQL 文件 vs 真库 sys_menu'
$sqlText = Get-Content -LiteralPath $MenuSql -Raw -Encoding UTF8
$sqlPerms = @([regex]::Matches($sqlText, "'(ctms:[a-z-]+:[a-z-]+)'") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
$sqlMenuIds = @([regex]::Matches($sqlText, "'(9F2C[0-9A-F]{28})'") | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
Info "菜单 SQL 声明权限点：$($sqlPerms.Count) 个；声明 menu_id：$($sqlMenuIds.Count) 行"

$dbRows = @(SqlRows "SELECT perms FROM sys_menu WHERE perms LIKE 'ctms:%' ORDER BY perms;" | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' })
$dbPerms = @($dbRows | Sort-Object -Unique)
Info "真库 ctms 权限点行数：$($dbRows.Count) 行 / 去重 $($dbPerms.Count) 个"

$idList = ($sqlMenuIds | ForEach-Object { "'$_'" }) -join ','
$dbBatchRows = @(SqlRows "SELECT COUNT(*) FROM sys_menu WHERE menu_id IN ($idList);" | ForEach-Object { ([string]$_).Trim() })
$dbBatchPerms = @(SqlRows "SELECT DISTINCT perms FROM sys_menu WHERE menu_id IN ($idList) AND perms LIKE 'ctms:%';" | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' } | Sort-Object -Unique)

Assert-That ($sqlPerms.Count -eq 26) "菜单 SQL 声明的权限点数 = 26（实际 $($sqlPerms.Count)）"
Assert-That ($sqlMenuIds.Count -eq 27) "菜单 SQL 声明的菜单行数 = 27（1 目录 + 4 菜单 + 22 按钮，实际 $($sqlMenuIds.Count)）"
Assert-That ($dbRows.Count -eq 26) "带 ctms 权限点的菜单行数 = 26（=权限点数；目录行 perms 为 NULL 不计数，实际 $($dbRows.Count)）"
Assert-That ($dbPerms.Count -eq $sqlPerms.Count -and (($dbPerms -join ',') -eq ($sqlPerms -join ','))) `
    "真库 perms 集合 == 菜单 SQL 声明集合（证明真库执行的就是本文件）"
Assert-That ($dbBatchRows.Count -eq 1 -and $dbBatchRows[0] -eq '27') `
    "本批 27 个 menu_id 在真库的行数 = 27（SELECT COUNT(*) 与文件自检①一致，实际 $($dbBatchRows[0])）"
Assert-That ($dbBatchPerms.Count -eq 26) "本批 menu_id 覆盖的 ctms 权限点数 = 26（实际 $($dbBatchPerms.Count)）"
Assert-That ($dbPerms -contains 'ctms:attachment:list') "第 6 组新增权限点 ctms:attachment:list 已在真库菜单行中（25 → 26 口径生效）"

# ------------------------------------------------------------------ 3. 后端侧：@PreAuthorize

Step '3. 后端侧集合 B：ruoyi-ctms 源码里的 ctms:* 注解'
$allHits = @()
$annoHits = @()
$javaFiles = @(Get-ChildItem -Path $JavaRoot -Recurse -Filter *.java -File | Sort-Object FullName)
foreach ($f in $javaFiles) {
    $n = 0
    foreach ($line in (Get-Content -LiteralPath $f.FullName -Encoding UTF8)) {
        $n++
        foreach ($m in [regex]::Matches($line, $PermPattern)) {
            $hit = [pscustomobject]@{
                Perm = $m.Value
                File = $f.Name
                Line = $n
                IsAnno = ($line -match 'hasPermi')
            }
            $allHits += $hit
            if ($hit.IsAnno) { $annoHits += $hit }
        }
    }
}
$backendPerms  = @($allHits  | ForEach-Object { $_.Perm } | Sort-Object -Unique)
$annoPerms     = @($annoHits | ForEach-Object { $_.Perm } | Sort-Object -Unique)
Info "扫描 .java 文件：$($javaFiles.Count) 个；权限点字符串出现 $($allHits.Count) 次（其中 @PreAuthorize/注解行 $($annoHits.Count) 次）"
Info "后端去重权限点：$($backendPerms.Count) 个"

Assert-That (($backendPerms -join ',') -eq ($annoPerms -join ',')) `
    '后端集合不依赖注释：全部命中集合 == 注解行集合（注释里提过的权限点不算"有实现"）'

# ------------------------------------------------------------------ 4. 双向差异（本任务的核心输出）

Step '4. 双向差异清单（两个方向都必须打印）'
$menuOnly = @($dbPerms    | Where-Object { $backendPerms -notcontains $_ })
$beOnly   = @($backendPerms | Where-Object { $dbPerms     -notcontains $_ })

Write-Host "    方向① 菜单有、后端无（共 $($menuOnly.Count) 个）：" -ForegroundColor Yellow
if ($menuOnly.Count -eq 0) { Info '      （空）' }
foreach ($p in $menuOnly) {
    $menuId = (SqlRows "SELECT menu_id FROM sys_menu WHERE perms = '$p';" | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -ne '' }) -join ','
    Write-Host "      - $p   [sys_menu.menu_id = $menuId]" -ForegroundColor Yellow
}
Write-Host "    方向② 后端有、菜单无（共 $($beOnly.Count) 个）：" -ForegroundColor Yellow
if ($beOnly.Count -eq 0) { Info '      （空）' }
foreach ($p in $beOnly) {
    $loc = ($annoHits | Where-Object { $_.Perm -eq $p } | ForEach-Object { "$($_.File):$($_.Line)" }) -join ', '
    Write-Host "      - $p   [$loc]" -ForegroundColor Yellow
}
Assert-That ($menuOnly.Count -eq 0) "方向① 菜单有后端无：差异为空（实际 $($menuOnly.Count) 个）"
Assert-That ($beOnly.Count -eq 0)   "方向② 后端有菜单无：差异为空（实际 $($beOnly.Count) 个）"
Assert-That ($dbPerms.Count -eq $backendPerms.Count -and (($dbPerms -join ',') -eq ($backendPerms -join ','))) `
    "集合 A == 集合 B（两侧各 $($dbPerms.Count) / $($backendPerms.Count) 个）"

# ------------------------------------------------------------------ 5. 逐权限点使用位置

Step '5. 逐权限点使用位置（控制器:行；菜单侧 26 个全列）'
$rows = @()
foreach ($p in $dbPerms) {
    $locs = @($annoHits | Where-Object { $_.Perm -eq $p })
    $desc = if ($locs.Count -eq 0) { '—（后端无使用）' } else { (@($locs | ForEach-Object { "$($_.File):$($_.Line)" }) -join ', ') }
    $rows += [pscustomobject]@{ 权限点 = $p; 次数 = $locs.Count; 使用位置 = $desc }
}
foreach ($r in $rows) { Write-Host ("    {0,-28} {1,2}  {2}" -f $r.权限点, $r.次数, $r.使用位置) }
if ($VerboseUsage) { Info '（-VerboseUsage 已给出全部位置；默认也全量打印，开关只为兼容调用习惯）' }

# ------------------------------------------------------------------ 6. 结论

Write-Host ''
Write-Host ('-' * 78)
if ($script:Fail -eq 0) {
    Write-Host "权限点双向核对通过：$script:Pass 条断言全绿，差异为空（菜单 26 / 后端 $($backendPerms.Count)，菜单行 27）。" -ForegroundColor Green
    exit 0
}
Write-Host "权限点双向核对未通过：$script:Pass 条通过 / $script:Fail 条失败。" -ForegroundColor Red
foreach ($f in $script:Failures) { Write-Host "  [!!] $f" -ForegroundColor Red }
Write-Host "  差异方向① 菜单有后端无：$($menuOnly.Count) 个 -> $($menuOnly -join ', ')" -ForegroundColor Red
Write-Host "  差异方向② 后端有菜单无：$($beOnly.Count) 个 -> $($beOnly -join ', ')" -ForegroundColor Red
Write-Host '  修法由任务负责人决定：本脚本只报告，不改 @PreAuthorize、不改菜单 SQL。' -ForegroundColor Red
exit 1
