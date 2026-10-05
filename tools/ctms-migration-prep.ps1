<#
================================================================================
 ctms-migration-prep.ps1 —— 合同台账「迁移准备」编排（B3 §7.5）
 需求：REQ-DATA-003（约束回补前必须产出数据体检清单）/ REQ-DATA-005（迁移前输出影响行数报告 + 快照）
 验收：AC-68；规格 ctms/contract-migration
--------------------------------------------------------------------------------
 把第 2 组的三份交付物串成**一条可重复执行、能自证**的入口，顺序固定：

   ① 体检（只读）                 体检-合同台账-<日期>.sql
   ② 体检不过 → 立刻停止           不生成快照、不执行 DDL；违例清单落盘并打印路径
   ③ 体检通过 → 影响行数报告 + 快照   影响行数报告-合同台账-<日期>.sql
   ④ 最后才是 DDL                 二开-合同台账.sql（建表 + 约束回补三段）

 ⚠ 为什么顺序不能换：`二开-合同台账.sql` 对 13 张业务表是 DROP + CREATE，且内含
   「回填 → 加 NOT NULL → 加 FK」。在脏数据上执行等于丢弃历史行（NULL 行无法满足 NOT NULL），
   所以「先体检」不是流程偏好而是数据安全前提；快照也必须在 DDL 之前拍，
   否则回滚脚本（回滚-合同台账-<日期>.sql）没有"动之前"的凭据。

 两种模式（夹具 = 编排开始**之前**装好的"历史态"模拟数据，不属于编排的执行阶段；
          编排的第一个动作永远是体检）：
   * 默认（健康存量库）    ：13 张表存在、13 个收紧目标列已收紧、数据干净
                             → 体检「需回填 0 列」→ 继续 → 报告+快照 → DDL → 断言约束已生效
   * -WithViolations（脏库）：额外把 13 个收紧目标列改回可空并写入**精确数量**的违例行
                             → 体检「需回填 13 列」→ 阻断（退出码 3）
                             → 断言"编排没有动过库"：快照 0 张、13 列仍可空（DDL 未执行）、
                               表名集合不变、夹具数据指纹不变

 用法：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-prep.ps1 -Database b3_migrate_prep_drill -WithViolations
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-prep.ps1 -Database b3_migrate_prep_drill

 退出码：0 成功（体检通过并跑完报告 + DDL）｜1 断言失败｜2 库名守卫拒绝｜3 体检未通过而阻断

 纪律（DEV-ENV §6）：
   * §6.33 含中文的 SQL **必须**走 stdin 管道喂给 mysql.exe（不能把中文文件名/中文 SQL 当命令行参数），
     且开头设 $OutputEncoding = UTF-8 —— PowerShell 5.1 默认 ASCII，中文列名会变成 `??`
     （实测报 `Duplicate column name '??'`）。
   * §6.35 判断"还剩几张表"不能用 `like 't_ctms\_%'`：那会把 `t_ctms_*_bak_<日期>` 快照表一起数进来。
   * §6.42 本脚本串行执行；演练库是一次性库（b3_*_drill），不与既有脚本并发共用。

 本脚本只**读**ruoyi-vue-oa-master\sql\ 下的交付物，不修改它们；自己只往 logs\ 落清单/演练记录。
================================================================================
#>
[CmdletBinding()]
param(
    # 一次性演练库名（脚本会在收尾 DROP 它；绝不指向业务库 rad_oa）
    [string]$Database = 'b3_migrate_prep_drill',
    # 可覆盖：mysql.exe / SQL 目录 / 日志目录（默认按脚本自身位置推导，不依赖调用方工作目录）
    [string]$MySqlCli = '',
    [string]$SqlDir   = '',
    [string]$LogDir   = '',
    [string]$Date     = '20261005',
    # 装"脏存量库"夹具：13 个收紧目标列改回可空 + 精确数量的违例行 → 期望体检阻断
    [switch]$WithViolations,
    # 仅排查用：保留演练库不 DROP（默认一律 DROP，符合"演练库收尾"纪律）
    [switch]$KeepDatabase
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# ⚠ 关键：`[Console]::OutputEncoding` 只管"读回原生命令输出"的解码；"把字符串管道喂给原生命令"
#   用的是 `$OutputEncoding`（PS 5.1 默认 ASCII）。设错会让 SQL 里的中文列名变成 ??（DEV-ENV §6.33）。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

# ---------------------------------------------------------------------------
# 自身路径：一律从脚本位置推导（不依赖调用方的工作目录）
# ---------------------------------------------------------------------------
$ScriptPath = $MyInvocation.MyCommand.Path
if (-not $ScriptPath) { $ScriptPath = $MyInvocation.MyCommand.Definition }
$ScriptDir = Split-Path -Parent $ScriptPath
$RepoRoot  = Split-Path -Parent $ScriptDir
if (-not $SqlDir)   { $SqlDir   = Join-Path $RepoRoot 'ruoyi-vue-oa-master\sql' }
if (-not $LogDir)   { $LogDir   = Join-Path $RepoRoot 'logs' }
if (-not $MySqlCli) { $MySqlCli = Join-Path $RepoRoot 'env\mysql\server\bin\mysql.exe' }

$health = Join-Path $SqlDir "体检-合同台账-$Date.sql"
$impact = Join-Path $SqlDir "影响行数报告-合同台账-$Date.sql"
$ddl    = Join-Path $SqlDir '二开-合同台账.sql'
$dictSql = Join-Path $SqlDir '二开-合同台账-字典参数.sql'
$numSql  = Join-Path $SqlDir '二开-合同台账-编号配置.sql'
$menuSql = Join-Path $SqlDir '二开-合同台账-菜单.sql'
$baselineTable = Join-Path $SqlDir 'table.sql'
$baselineData  = Join-Path $SqlDir 'data.sql'

# 本变更集的 13 张业务表（精确清单：不用 like 't_ctms\_%'，见 DEV-ENV §6.35）
$ctmsTables = @('t_ctms_contract','t_ctms_contract_item','t_ctms_tag','t_ctms_contract_tag',
                't_ctms_change_log','t_ctms_attachment','t_ctms_customer','t_ctms_supplier',
                't_ctms_party_draft','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse','t_ctms_product')
$ctmsIn = "table_name in ('" + ($ctmsTables -join "','") + "')"

# 体检覆盖的 13 个"收紧目标列"（与 体检-合同台账-<日期>.sql 逐条对应；也用于 DDL 后断言已收紧）
$targets = @(
    @('t_ctms_contract','dept_id'), @('t_ctms_contract','create_id'),
    @('t_ctms_contract_item','product_id'), @('t_ctms_contract_item','product_code'),
    @('t_ctms_contract_item','product_name'),
    @('t_ctms_change_log','object_type'), @('t_ctms_change_log','object_id'),
    @('t_ctms_attachment','object_type'), @('t_ctms_attachment','object_id'),
    @('t_ctms_customer','create_id'), @('t_ctms_supplier','short_name'),
    @('t_ctms_supplier','create_id'), @('t_ctms_product','create_id')
)

# ---------------------------------------------------------------------------
# 输出与自证
# ---------------------------------------------------------------------------
$script:Pass = 0; $script:Fail = 0; $script:Fails = @(); $script:Blocked = $false
$script:Log = New-Object System.Collections.Generic.List[string]
function Add-Log([string]$line) { $script:Log.Add($line) | Out-Null }
function Step($m) { $t = "`n==> $m"; Write-Host $t -ForegroundColor Cyan; Add-Log $t }
function Ok($m)   { $t = "    [OK] $m"; Write-Host $t -ForegroundColor Green; Add-Log $t; $script:Pass++ }
function Bad($m)  { $t = "    [!!] $m"; Write-Host $t -ForegroundColor Red; Add-Log $t; $script:Fail++; $script:Fails += $m }
function Info($m) { $t = "    $m"; Write-Host $t -ForegroundColor DarkGray; Add-Log $t }
function Assert-That([bool]$c, [string]$d) { if ($c) { Ok $d } else { Bad $d } }
function Save-Log([string]$path) {
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($path, ($script:Log -join "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
}

# ---------------------------------------------------------------------------
# MySQL 调用：文件走 stdin（中文 SQL 绝不当命令行参数）
# ---------------------------------------------------------------------------
function RunFile([string]$path, [string]$Db, [switch]$AllowError) {
    if (-not (Test-Path $path)) { throw "缺少文件：$path" }
    # mysql.exe 把错误写 stderr；在 $ErrorActionPreference='Stop' 下 PS 会把它变成
    # NativeCommandError 直接中断脚本，让"允许出错"的分支永远走不到。这里局部放开。
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = (Get-Content -Raw -Encoding UTF8 $path) | & $MySqlCli --host=127.0.0.1 --user=root "--database=$Db" --default-character-set=utf8mb4 2>&1
    $ErrorActionPreference = $old
    $text = ($out -join "`n")
    $hasErr = [bool]($text -match 'ERROR \d{4}')
    if ($hasErr -and -not $AllowError) {
        Write-Host ("    !! 出错文件：" + (Split-Path $path -Leaf)) -ForegroundColor Red
        Write-Host $text -ForegroundColor DarkRed
        Add-Log ("    !! 出错文件：" + (Split-Path $path -Leaf))
        Add-Log $text
    }
    return @{ text = $text; err = $hasErr }
}
function Q([string]$Db, [string]$sql) {
    $v = (& $MySqlCli --host=127.0.0.1 --user=root "--database=$Db" --batch --skip-column-names --default-character-set=utf8mb4 -e $sql 2>&1) |
         Where-Object { $_ -notmatch 'Warning' } | Select-Object -First 1
    if ($null -eq $v) { return '' }
    return ([string]$v).Trim()
}
function QAll([string]$Db, [string]$sql) {
    $v = & $MySqlCli --host=127.0.0.1 --user=root "--database=$Db" --batch --skip-column-names --default-character-set=utf8mb4 -e $sql 2>&1
    return @($v | Where-Object { $_ -ne $null })
}
function Exec([string]$Db, [string]$sql) {
    $out = & $MySqlCli --host=127.0.0.1 --user=root "--database=$Db" --default-character-set=utf8mb4 -e $sql 2>&1
    return @{ text = ($out -join "`n"); err = [bool](($out -join "`n") -match 'ERROR \d{4}') }
}
# 多语句脚本走临时文件 + stdin（避免命令行长度与转义问题）
function RunScript([string]$Db, [string]$sql) {
    $tmp = Join-Path $env:TEMP ('ctmsprep-' + [guid]::NewGuid().ToString('N') + '.sql')
    [System.IO.File]::WriteAllText($tmp, $sql, (New-Object System.Text.UTF8Encoding($false)))
    $r = RunFile $tmp $Db
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    return $r
}
# 业务表数据指纹（证明"阻断时编排没改过一行数据"）
function Fingerprint([string]$Db) {
    $parts = @()
    foreach ($t in @('t_ctms_contract','t_ctms_contract_item','t_ctms_customer','t_ctms_supplier','t_ctms_product','t_ctms_change_log','t_ctms_attachment','t_ctms_party_draft')) {
        $n = Q $Db "select count(*) from $t"
        $h = Q $Db "select ifnull(md5(group_concat(id order by id separator '|')),'-') from $t"
        $parts += "$t=$n/$h"
    }
    return ($parts -join ';')
}
# 库内表名集合（证明"阻断时没有新增/减少表"）
function TableNames([string]$Db) {
    $rows = QAll $Db "select table_name from information_schema.tables where table_schema='$Db' order by table_name"
    return (($rows | ForEach-Object { ([string]$_).Trim() }) -join ',')
}

# ---------------------------------------------------------------------------
# 0. 路径与库名守卫
# ---------------------------------------------------------------------------
Step '0. 路径解析与库名守卫'
Info "脚本路径（自身推导，不依赖调用方工作目录）= $ScriptPath"
Info "仓库根        = $RepoRoot"
Info "SQL 目录      = $SqlDir"
Info "日志目录      = $LogDir"
Info "mysql.exe     = $MySqlCli"
Info "连接库名      = $Database"
$modeText = $(if ($WithViolations) { '脏存量库（-WithViolations，期望体检阻断）' } else { '健康存量库（期望体检通过并跑完报告+DDL）' })
Info "模式          = $modeText"

foreach ($f in @($health, $impact, $ddl, $dictSql, $numSql, $menuSql, $baselineTable, $baselineData)) {
    if (-not (Test-Path $f)) { throw "缺少第 2 组交付物：$f" }
}
Assert-That (Test-Path $MySqlCli) "mysql.exe 存在"

$protected = @('rad_oa','mysql','information_schema','performance_schema','sys')
if ($protected -contains $Database.ToLower()) {
    Write-Host "[拒接] 目标库 '$Database' 是受保护库（业务库/系统库），本脚本只允许在一次性演练库上执行。" -ForegroundColor Red
    Add-Log "[拒接] 目标库 '$Database' 是受保护库，拒绝执行。"
    exit 2
}
if ($Database -notmatch '^[A-Za-z0-9_]+$') {
    Write-Host "[拒接] 目标库名 '$Database' 含非法字符（只允许字母/数字/下划线）。" -ForegroundColor Red
    exit 2
}
Ok "库名守卫通过（非 rad_oa / 非系统库 / 字符合法）"

$Mode = if ($WithViolations) { 'blocked' } else { 'clean' }
$logPath = Join-Path $LogDir "ctms-migration-prep-$Mode.log"

# ---------------------------------------------------------------------------
# 1. 装夹具（编排开始前的"历史态"模拟数据）
# ---------------------------------------------------------------------------
Step '1. 装夹具：把演练库装成"变更集落地前/后"的历史态（不属于编排的执行阶段）'
$exists = Q 'mysql' "select count(*) from information_schema.schemata where schema_name='$Database'"
if ($exists -eq '0') {
    $r = Exec 'mysql' "CREATE DATABASE $Database DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
    Assert-That (-not $r.err) "演练库已创建：$Database"
} else {
    Info "演练库已存在，复用（不 DROP：连续执行两次要能跑在同一个库上）"
}
$baseTables = Q $Database "select count(*) from information_schema.tables where table_schema='$Database'"
if ([int]$baseTables -lt 100) {
    $r = RunFile $baselineTable $Database; Assert-That (-not $r.err) 'table.sql（上游基线）载入无错误'
    $r = RunFile $baselineData  $Database; Assert-That (-not $r.err) 'data.sql（上游基线数据）载入无错误'
} else {
    Info "基线已就位（$baseTables 张表），跳过载入"
}
$USERID = Q $Database "select user_id from sys_user order by (user_id='superAdmin') desc, user_id limit 1"
$DEPTID = Q $Database "select dept_id from sys_dept order by char_length(ifnull(ancestors,'')), dept_id limit 1"
Assert-That ($USERID -ne '' -and $DEPTID -ne '') "取到基线父行 id（user=$USERID / dept=$DEPTID）"

# 建表入口只有变更集自己的 DDL（它对 13 张表是 DROP + CREATE，天然幂等）
$r = RunFile $ddl $Database; Assert-That (-not $r.err) '二开-合同台账.sql（建表 + 约束回补）执行无错误'
$cnt = Q $Database "select count(*) from information_schema.tables where table_schema='$Database' and $ctmsIn"
Assert-That ($cnt -eq '13') "13 张业务表齐备（实际 $cnt）"
$r = RunFile $dictSql $Database; Assert-That (-not $r.err) '字典参数脚本执行无错误'
$r = RunFile $numSql  $Database; Assert-That (-not $r.err) '编号配置脚本执行无错误'
$r = RunFile $menuSql $Database; Assert-That (-not $r.err) '菜单脚本执行无错误'

# 夹具数据：先删后插（固定 id，可重复执行）
$fixture = @"
DELETE FROM t_ctms_contract_tag WHERE contract_id='DRLCT1';
DELETE FROM t_ctms_tag WHERE id='DRLT1';
DELETE FROM t_ctms_attachment WHERE id='DRLA1';
DELETE FROM t_ctms_change_log WHERE id='DRLL1';
DELETE FROM t_ctms_contract_item WHERE id IN ('DRLI1','DRLI2','DRLI3');
DELETE FROM t_ctms_contract WHERE id='DRLCT1';
DELETE FROM t_ctms_supplier WHERE id IN ('DRLS1','DRLS2');
DELETE FROM t_ctms_product WHERE id='DRLP1';
DELETE FROM t_ctms_uom WHERE id='DRLUOM1';
DELETE FROM t_ctms_product_type WHERE id='DRLPT1';
DELETE FROM t_ctms_customer WHERE id IN ('DRLC1','DRLC2');
DELETE FROM t_ctms_party_draft WHERE id='DRLPD1';
INSERT INTO t_ctms_customer (id, code, name, short_name, create_id) VALUES ('DRLC1','DRLC1','迁移客户1','简1','$USERID'), ('DRLC2','DRLC2','迁移客户2','简2','$USERID');
INSERT INTO t_ctms_product_type (id, code, name, path, level) VALUES ('DRLPT1','DRLPT1','迁移类型','/DRLPT1/',1);
INSERT INTO t_ctms_uom (id, code, name, decimals) VALUES ('DRLUOM1','DRLUOM1','个',0);
INSERT INTO t_ctms_product (id, code, name, product_type_id, uom_id, create_id) VALUES ('DRLP1','DRLP1','迁移物料','DRLPT1','DRLUOM1','$USERID');
INSERT INTO t_ctms_supplier (id, code, name, short_name, create_id) VALUES ('DRLS1','DRLS1','迁移供应商1','简1','$USERID'), ('DRLS2','DRLS2','迁移供应商2','简2','$USERID');
INSERT INTO t_ctms_party_draft (id, party_type, raw_name) VALUES ('DRLPD1','supplier','某某阀门有限公司');
INSERT INTO t_ctms_tag (id, name, color, builtin) VALUES ('DRLT1','迁移标签','#409eff','0');
INSERT INTO t_ctms_contract (id, contract_no, name, subject_matter, dept_id, create_id) VALUES ('DRLCT1','DRLCT1','迁移合同','迁移标的物','$DEPTID','$USERID');
INSERT INTO t_ctms_contract_item (id, contract_id, seq, item_type, name, spec, qty, unit_price, total, product_id, product_code, product_name) VALUES
 ('DRLI1','DRLCT1',1,'采购','行项1','',1,1,1,'DRLP1','DRLP1','迁移物料'),
 ('DRLI2','DRLCT1',2,'采购','行项2','',1,1,1,'DRLP1','DRLP1','迁移物料'),
 ('DRLI3','DRLCT1',3,'采购','行项3','',1,1,1,'DRLP1','DRLP1','迁移物料');
INSERT INTO t_ctms_contract_tag (contract_id, tag_id, auto, create_id, create_by, create_time) VALUES ('DRLCT1','DRLT1','0','$USERID','system',sysdate());
INSERT INTO t_ctms_change_log (id, contract_id, field_name, old_value, new_value, source, object_type, object_id) VALUES ('DRLL1','DRLCT1','name','a','b','manual','contract','DRLCT1');
INSERT INTO t_ctms_attachment (id, contract_id, file_name, stored_path, size_bytes, object_type, object_id, del_flag) VALUES ('DRLA1','DRLCT1','a.pdf','/profile/a.pdf',1,'contract','DRLCT1','0');
"@
$r = RunScript $Database $fixture
Assert-That (-not $r.err) '夹具业务数据写入完成（固定 id，可重复执行）'

# -WithViolations：把 13 个收紧目标列改回可空，并写入精确数量的违例行
$expectedNulls = @(
    @('t_ctms_contract','dept_id',1), @('t_ctms_contract','create_id',1),
    @('t_ctms_contract_item','product_id',3), @('t_ctms_contract_item','product_code',3),
    @('t_ctms_contract_item','product_name',3),
    @('t_ctms_change_log','object_type',1), @('t_ctms_change_log','object_id',1),
    @('t_ctms_attachment','object_type',1), @('t_ctms_attachment','object_id',1),
    @('t_ctms_customer','create_id',2), @('t_ctms_supplier','short_name',2),
    @('t_ctms_supplier','create_id',2), @('t_ctms_product','create_id',1)
)
if ($WithViolations) {
    $colDdl = ''
    foreach ($n in $expectedNulls) {
        $t, $c, $k = $n
        $type = Q $Database "select column_type from information_schema.columns where table_schema='$Database' and table_name='$t' and column_name='$c'"
        if (-not $type) { Bad "夹具列不存在：${t}.${c}"; continue }
        $charset = Q $Database "select ifnull(character_set_name,'') from information_schema.columns where table_schema='$Database' and table_name='$t' and column_name='$c'"
        $coll    = Q $Database "select ifnull(collation_name,'') from information_schema.columns where table_schema='$Database' and table_name='$t' and column_name='$c'"
        $typeSql = $type
        if ($charset) { $typeSql += " CHARACTER SET $charset" }
        if ($coll)    { $typeSql += " COLLATE $coll" }
        $colDdl += "ALTER TABLE $t MODIFY COLUMN $c $typeSql NULL;`n"
    }
    $dirty = @"
$colDdl
UPDATE t_ctms_contract SET dept_id=NULL, create_id=NULL WHERE id='DRLCT1';
UPDATE t_ctms_contract_item SET product_id=NULL, product_code=NULL, product_name=NULL WHERE id IN ('DRLI1','DRLI2','DRLI3');
UPDATE t_ctms_change_log SET object_type=NULL, object_id=NULL WHERE id='DRLL1';
UPDATE t_ctms_attachment SET object_type=NULL, object_id=NULL WHERE id='DRLA1';
UPDATE t_ctms_customer SET create_id=NULL WHERE id IN ('DRLC1','DRLC2');
UPDATE t_ctms_supplier SET short_name=NULL, create_id=NULL WHERE id IN ('DRLS1','DRLS2');
UPDATE t_ctms_product SET create_id=NULL WHERE id='DRLP1';
"@
    $r = RunScript $Database $dirty
    Assert-That (-not $r.err) '脏库夹具装好（13 列已改回可空 + 精确数量违例行）'
} else {
    Info '健康存量库：不装违例（13 列保持已收紧）'
}

# 编排基线：表名集合 + 指纹（阻断时用来证明"编排没动过库"）
$preTables = TableNames $Database
$preFingerprint = Fingerprint $Database
$preSnapshots = Q $Database "select count(*) from information_schema.tables where table_schema='$Database' and table_name like '%_bak_%'"
Info "编排开始前：表 $(( $preTables -split ',').Count) 张；快照表 $preSnapshots 张；业务表指纹 $preFingerprint"

# ---------------------------------------------------------------------------
# 2. ① 体检（只读）
# ---------------------------------------------------------------------------
Step '2. ① 体检（只读；体检-合同台账-<日期>.sql）'
$fpBefore = Fingerprint $Database
$h = RunFile $health $Database
Assert-That (-not $h.err) '体检脚本执行无错误'
Assert-That ((Fingerprint $Database) -eq $fpBefore) '体检执行前后业务表数据零变化（只读承诺）'
Add-Log '    ---- 体检原始输出 ----'
foreach ($line in ($h.text -split "`r?`n")) { Add-Log "    $line" }

$veto = 0
$conclusion = (($h.text -split "`r?`n") | Where-Object { $_ -like '*需回填 *列*' -and $_ -like '*共核对*' } | Select-Object -Last 1)
if ($conclusion -and $conclusion -match '需回填\s+(\d+)\s+列') { $veto = [int]$Matches[1] }
Assert-That ($null -ne $conclusion) "解析到体检结论行：$conclusion"

# ⚠ 阻断判据取【逐列违例行数之和 > 0】，而不是结论行里的「需回填 N 列」：
#   体检脚本的状态档是「列当前可空 → 需回填（违例行数可能是 0）」「列已收紧 → 可加约束」，
#   所以"可空但一行 NULL 都没有"的列也会落进『需回填』那一档。规格的口径是
#   「体检对全部待加约束列均返回 0 违例 → 允许继续」，因此按**违例行数**判定才等价：
#   有 1 行违例就阻断（哪怕该列本来就可空），0 违例就放行（哪怕列仍可空、等着 DDL 去收紧）。
$checked = 0
$violations = 0
foreach ($line in ($h.text -split "`r?`n")) {
    if ($line -match '^(t_ctms_[a-z_]+)\t([a-z_]+)\t(\d+)\t(需回填|可加约束|不适用|已收紧)') {
        $checked++
        $violations += [int]$Matches[3]
    }
}
Assert-That ($checked -eq 13) "体检覆盖全部 13 个待加约束列（实际 $checked 列）"
Info "体检结论：需回填 $veto 列；违例行数合计 $violations 行（0 = 通过）"

# 先核对"夹具与预期一致"（模式只是选择装哪种夹具；真正的流程分支由体检结果决定）
$expectedTotal = ($expectedNulls | ForEach-Object { $_[2] } | Measure-Object -Sum).Sum
if ($WithViolations) {
    # 逐列核对体检报出的违例行数与夹具造数一致（规格："输出含这 7 行主键的违例清单"）
    $mismatch = @()
    foreach ($n in $expectedNulls) {
        $t, $c, $expected = $n
        $got = ($h.text -split "`r?`n") | Where-Object { $_ -like "$t`t$c`t*" } | Select-Object -First 1
        $actual = if ($got) { [int](($got -split "`t")[2]) } else { -1 }
        if ($actual -ne $expected) { $mismatch += "${t}.${c} 期望 $expected 实际 $actual" }
    }
    Assert-That ($mismatch.Count -eq 0) ("体检违例行数与夹具造数逐列一致（13 列）" + $(if ($mismatch.Count) { ' → ' + ($mismatch -join '; ') } else { '' }))
    Assert-That ($violations -eq $expectedTotal) "体检违例行数合计 $violations 行 = 夹具造数合计 $expectedTotal 行"
    Assert-That ($violations -gt 0) '脏库夹具下体检必须报出违例（否则夹具或体检脚本已变）'
} else {
    Assert-That ($violations -eq 0) '健康存量库下体检必须 0 违例（否则夹具或体检脚本已变）'
}

# ---------------------------------------------------------------------------
# ① → 流程分支：判据是【体检结果】，不是命令行开关。
#    有违例 → 阻断（不生成快照、不执行 DDL）；0 违例 → 继续。
# ---------------------------------------------------------------------------
if ($violations -gt 0) {
    # 阻断分支（-WithViolations 是常规入口；默认模式下若意外发现违例也走这里并阻断）
    $script:Blocked = $true

    # 落盘违例清单：列名 + 违例行数 + 可定位主键/违例值（体检输出的第二/三部分原样保留）
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
    $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
    $listText = @()
    $listText += "合同台账迁移准备 · 违例清单（体检未通过，已阻断）"
    $listText += "生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    $listText += "连接库名：$Database"
    $listText += "编排脚本：$ScriptPath"
    $listText += "体检脚本：$health"
    $listText += "阻断原因：体检报出『需回填 $veto 列』、违例行数合计 $violations 行。按口径必须先回填（见下方明细的列名/违例行数/主键/违例值），"
    $listText += "           再重跑本脚本；本次未生成任何快照、未执行任何 DDL。"
    $listText += ""
    $listText += "---- 体检原始输出（第二/三部分含逐列违例行数与可定位主键） ----"
    $listText += ($h.text -split "`n")
    $listFile = Join-Path $LogDir "ctms-migration-prep-violations-$Database-$ts.txt"
    $listLatest = Join-Path $LogDir "ctms-migration-prep-violations-$Database-latest.txt"
    [System.IO.File]::WriteAllText($listFile, ($listText -join "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
    [System.IO.File]::WriteAllText($listLatest, (($listText -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
    Info "违例清单已落盘：$listFile"
    Info "违例清单（稳定路径，供人工直接打开）：$listLatest"
    Assert-That (Test-Path $listFile) '违例清单文件已生成'
    $listBody = [System.IO.File]::ReadAllText($listFile, [System.Text.Encoding]::UTF8)
    if ($WithViolations) {
        Assert-That ($listBody -match 'DRLCT1') '清单含可定位主键（示例：DRLCT1）'
        Assert-That ($listBody -match 'dept_id' -and $listBody -match 'short_name') '清单含列名（示例：dept_id / short_name）'
    }

    # 阻断断言：编排除了"读"以外什么都没做
    $postTables = TableNames $Database
    $postFingerprint = Fingerprint $Database
    $postSnapshots = Q $Database "select count(*) from information_schema.tables where table_schema='$Database' and table_name like '%_bak_%'"
    $stillNullable = 0
    foreach ($n in $targets) {
        $t, $c = $n
        $nullable = Q $Database "select ifnull(is_nullable,'') from information_schema.columns where table_schema='$Database' and table_name='$t' and column_name='$c'"
        if ($nullable -eq 'YES') { $stillNullable++ }
    }
    Assert-That ($postSnapshots -eq '0') "阻断：未生成任何快照表（*_bak_* = $postSnapshots 张）"
    Assert-That ($stillNullable -eq 13) "阻断：13 个收紧目标列仍全部可空（$stillNullable/13）→ DDL 的『加 NOT NULL』阶段未执行"
    Assert-That ($postTables -eq $preTables) '阻断：编排前后表名集合完全一致（未新建/未删除任何表）'
    Assert-That ($postFingerprint -eq $preFingerprint) '阻断：夹具业务表数据指纹一致（未跑 DDL 的回填/加约束）'

    Write-Host ""
    Write-Host "[阻断] 体检未通过：需回填 $veto 列、违例行数合计 $violations 行。按规格 MUST NOT 执行加约束 DDL；本次未生成快照、未执行 DDL。" -ForegroundColor Yellow
    Add-Log "[阻断] 体检未通过：需回填 $veto 列、违例行数合计 $violations 行。按规格 MUST NOT 执行加约束 DDL；本次未生成快照、未执行 DDL。"
    Write-Host ("       违例清单（含列名/违例行数/可定位主键）：" + $listFile) -ForegroundColor Yellow
    Add-Log ("       违例清单（含列名/违例行数/可定位主键）：" + $listFile)
} else {
    Assert-That ($violations -eq 0) "体检全通过（违例行数合计 0 行，覆盖 13 列）→ 允许进入快照与 DDL 阶段"

    # -----------------------------------------------------------------------
    # 3. ② 影响行数报告 + 快照（跑两次：证明含 DROP 再建快照的幂等）
    # -----------------------------------------------------------------------
    Step '3. ② 影响行数报告 + 快照（影响行数报告-合同台账-<日期>.sql）'
    $i1 = RunFile $impact $Database
    Assert-That (-not $i1.err) '影响行数报告执行无错误（第 1 次）'
    Add-Log '    ---- 影响行数报告原始输出 ----'
    foreach ($line in ($i1.text -split "`r?`n")) { Add-Log "    $line" }

    Add-Log '    ---- 报告 vs 真库 COUNT(*) 逐表核对 ----'
    $reportCounts = @{}
    $reportRows = 0
    $badRows = @()
    foreach ($line in ($i1.text -split "`r?`n")) {
        if ($line -match '^业务表（本变更集新建）\t(t_ctms_[a-z_]+)\t(\d+)\t(\S+)\t(\d+)\t') {
            $t = $Matches[1]; $affected = [int]$Matches[2]; $snap = [int]$Matches[4]
            $reportCounts[$t] = @{ affected = $affected; snapshot = $snap }
            $reportRows++
            $live = [int](Q $Database "select count(*) from $t")
            $snapName = "${t}_bak_$Date"
            $snapLive = [int](Q $Database "select count(*) from $snapName")
            if ($affected -ne $live)     { $badRows += "$t 受影响行数 $affected ≠ COUNT(*) $live" }
            if ($snap -ne $affected)     { $badRows += "$t 快照行数 $snap ≠ 受影响行数 $affected" }
            if ($snapLive -ne $snap)     { $badRows += "$t 快照表实查 $snapLive ≠ 报告 $snap" }
        }
    }
    Assert-That ($reportRows -eq 13) "报告覆盖 13 张业务表（实际 $reportRows 行）"
    Assert-That ($badRows.Count -eq 0) ("报告行数与真库 COUNT(*) 逐表一致（13 张）" + $(if ($badRows.Count) { ' → ' + ($badRows -join '; ') } else { '' }))
    if ($badRows.Count) { foreach ($b in $badRows) { Add-Log "      [!!] $b" } }
    foreach ($t in $ctmsTables) {
        if ($reportCounts.ContainsKey($t)) { Add-Log ("      " + $t + " 受影响=" + $reportCounts[$t].affected + " 快照=" + $reportCounts[$t].snapshot) }
    }
    Assert-That ($i1.text -match '通过：所有已生成快照的行数都与受影响行数一致') '报告自带的一致性核对结论为「通过」'
    Assert-That ($i1.text -match '通过：需要快照的行都已生成快照') '报告自带的快照完整性结论为「通过」'

    $i2 = RunFile $impact $Database
    Assert-That (-not $i2.err) '影响行数报告执行无错误（第 2 次，验证 DROP 再建快照的幂等）'
    $same = $true
    foreach ($t in $ctmsTables) {
        $l2 = ($i2.text -split "`r?`n") | Where-Object { $_ -like "*`t$t`t*" } | Select-Object -First 1
        if ($l2 -and $reportCounts.ContainsKey($t)) {
            $fields = $l2 -split "`t"
            if ([int]$fields[2] -ne $reportCounts[$t].affected) { $same = $false; Bad "第 2 次报告的受影响行数变了：$t" }
            if ([int]$fields[4] -ne $reportCounts[$t].snapshot) { $same = $false; Bad "第 2 次报告的快照行数变了：$t" }
        }
    }
    Assert-That $same '连续两次报告结果一致（快照 DROP 再建，幂等）'

    # -----------------------------------------------------------------------
    # 4. ③ DDL（建表 + 约束回补三段；最后一步）
    # -----------------------------------------------------------------------
    Step '4. ③ DDL（二开-合同台账.sql；跑两次验证幂等）'
    $d1 = RunFile $ddl $Database
    Assert-That (-not $d1.err) 'DDL 第 1 次执行无错误'
    $d2 = RunFile $ddl $Database
    Assert-That (-not $d2.err) 'DDL 第 2 次执行无错误（幂等）'
    $cnt2 = Q $Database "select count(*) from information_schema.tables where table_schema='$Database' and $ctmsIn"
    Assert-That ($cnt2 -eq '13') "DDL 后 13 张业务表齐备（实际 $cnt2）"
    $tight = 0
    foreach ($n in $targets) {
        $t, $c = $n
        $nullable = Q $Database "select ifnull(is_nullable,'') from information_schema.columns where table_schema='$Database' and table_name='$t' and column_name='$c'"
        if ($nullable -eq 'NO') { $tight++ }
    }
    Assert-That ($tight -eq 13) "13 个收紧目标列已全部收紧为 NOT NULL（$tight/13）"
    # ⚠ 先把 SQL 拼进变量再调用（不要在"命令参数"位置用 `+` 拼接：命令模式下 `+` 会被当成独立参数，
    #   传给 mysql.exe 的 SQL 会被截断 → 实测报 `ERROR 1064 ... near '''`）。
    $ctmsListSql = "'" + ($ctmsTables -join "','") + "'"
    $fkSql = "select count(*) from information_schema.key_column_usage where constraint_schema='$Database' and referenced_table_name is not null and (table_name in ($ctmsListSql) or referenced_table_name in ($ctmsListSql))"
    $fk = Q $Database $fkSql
    Assert-That ([int]$fk -gt 0) "本变更集相关外键已建立（$fk 条）"
    Add-Log "    DDL 后自证：业务表 $cnt2 张；收紧列 $tight/13；相关外键 $fk 条"
}

# ---------------------------------------------------------------------------
# 5. 收尾
# ---------------------------------------------------------------------------
Step '5. 收尾'
Info "断言行数：通过 $script:Pass / 失败 $script:Fail"
if ($script:Fail -gt 0) { foreach ($f in $script:Fails) { Add-Log "      [!!] $f" } }

Save-Log $logPath
Write-Host "    演练记录（原始输出）：$logPath" -ForegroundColor DarkGray

if ($KeepDatabase) {
    Info "-KeepDatabase：保留演练库 $Database（默认行为是收尾 DROP，便于人工排查时才用）"
} else {
    $r = Exec 'mysql' "DROP DATABASE IF EXISTS $Database;"
    Assert-That (-not $r.err) "演练库已收尾 DROP DATABASE：$Database"
    Assert-That ((Q 'mysql' "select count(*) from information_schema.schemata where schema_name='$Database'") -eq '0') '确认演练库已不存在'
    Add-Log "    收尾：DROP DATABASE $Database（未触碰 rad_oa）"
    Save-Log $logPath
}

Write-Host ""
if ($script:Blocked) {
    Write-Host ("体检阻断路径演练结束：断言 通过 " + $script:Pass + " / 失败 " + $script:Fail + "（退出码 3 = 体检未通过而阻断，未生成快照、未执行 DDL）") -ForegroundColor Yellow
    if ($script:Fail -gt 0) { exit 1 }
    exit 3
}
Write-Host ("体检通过路径演练结束：断言 通过 " + $script:Pass + " / 失败 " + $script:Fail) -ForegroundColor $(if ($script:Fail -gt 0) { 'Red' } else { 'Green' })
if ($script:Fail -gt 0) { exit 1 }
exit 0
