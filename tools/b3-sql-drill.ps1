<#
================================================================================
 b3-sql-drill.ps1 —— B3 SQL 四件套的独立演练（§2.2 / §2.3 / §2.5 的验收）
--------------------------------------------------------------------------------
 为什么要独立库演练：
   体检/快照/回滚三个脚本的价值全在"出错时能不能把库还原"，而这只能在**真实执行**里证明。
   在 rad_oa（业务库）上演练会污染开发数据，所以在一次性库 b3_rollback_drill 上做，
   收尾 DROP DATABASE。

 覆盖：
   A. 建库 → 载入上游基线(table.sql) → 跑 B3 建表脚本（13 张表齐备）
   B. 执行字典/编号/菜单种子（让回滚有东西可撤）
   C. §2.2 体检：人为把 13 列改回可空 + 造违例 → 体检报告的违例行数必须**逐列等于**造数，
      且执行前后业务表数据**零变化**
   D. §2.3 影响行数报告：生成快照；快照行数必须等于受影响行数
   E. §2.5 回滚·中止守卫：13 张表有数据但删掉快照 → 回滚必须**失败**（不允许"回滚一半"）
   F. §2.5 完整回滚：对象全部消失 + 存量表回到执行前状态（按快照逐表核对行数）
   G. §2.5 CONSTRAINTS_ONLY：只撤约束（表还在、FK 没了、13 列恢复可空）

 用法：powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\b3-sql-drill.ps1
================================================================================
#>
[CmdletBinding()]
param(
    [string]$MySqlCli = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$SqlDir   = 'F:\dsh\ruoyiOA\ruoyi-vue-oa-master\sql',
    [string]$DbName = 'b3_rollback_drill',
    [string]$Date     = '20261005'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# ⚠ 关键：`[Console]::OutputEncoding` 只管"读回原生命令输出"的解码，
#   而"把字符串管道喂给原生命令"用的是 `$OutputEncoding` —— PowerShell 5.1 默认是 **ASCII**，
#   于是 SQL 里的中文列名会被换成 `?`（实测报 `Duplicate column name '??'`：
#   `表名`/`列名` 都变成 `??`）。被 `powershell -File` 拉起时这个坑必现。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$script:Pass = 0; $script:Fail = 0; $script:Fails = @()
function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "    [OK] $m" -ForegroundColor Green; $script:Pass++ }
function Bad($m)  { Write-Host "    [!!] $m" -ForegroundColor Red; $script:Fail++; $script:Fails += $m }
function Info($m) { Write-Host "    $m" -ForegroundColor DarkGray }
function Assert-That([bool]$c, [string]$d) { if ($c) { Ok $d } else { Bad $d } }

# 用文件喂 SQL；返回 (输出文本, 是否出错)
function RunFile([string]$path, [string]$DbName, [switch]$AllowError) {
    if (-not (Test-Path $path)) { throw "缺少文件：$path" }
    # ⚠ mysql.exe 把错误写到 stderr；在 $ErrorActionPreference='Stop' 下 PowerShell 会把它
    #   变成 NativeCommandError 直接中断脚本，导致"允许出错"的分支永远走不到。这里局部放开。
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = (Get-Content -Raw -Encoding UTF8 $path) | & $MySqlCli --host=127.0.0.1 --user=root "--database=$DbName" --default-character-set=utf8mb4 2>&1
    $ErrorActionPreference = $old
    $text = ($out -join "`n")
    $hasErr = [bool]($text -match 'ERROR \d{4}')
    if ($hasErr -and -not $AllowError) {
        Write-Host ("    !! 出错文件：" + (Split-Path $path -Leaf)) -ForegroundColor Red
        Write-Host $text -ForegroundColor DarkRed
    }
    return @{ text = $text; err = $hasErr }
}
function Q([string]$DbName, [string]$sql) {
    $v = (& $MySqlCli --host=127.0.0.1 --user=root "--database=$DbName" --batch --skip-column-names --default-character-set=utf8mb4 -e $sql 2>&1) |
         Where-Object { $_ -notmatch 'Warning' } | Select-Object -First 1
    if ($null -eq $v) { return '' }
    return ([string]$v).Trim()
}
function Exec([string]$DbName, [string]$sql) { & $MySqlCli --host=127.0.0.1 --user=root "--database=$DbName" --default-character-set=utf8mb4 -e $sql 2>&1 | Out-Null }
function Script([string]$DbName, [string]$sql) {
    $tmp = Join-Path $env:TEMP ('b3drill-' + [guid]::NewGuid().ToString('N') + '.sql')
    [System.IO.File]::WriteAllText($tmp, $sql, (New-Object System.Text.UTF8Encoding($false)))
    $out = (Get-Content -Raw -Encoding UTF8 $tmp) | & $MySqlCli --host=127.0.0.1 --user=root "--database=$DbName" --default-character-set=utf8mb4 2>&1
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    $text = ($out -join "`n")
    return @{ text = $text; err = [bool]($text -match 'ERROR \d{4}') }
}
# 业务表数据指纹（用于"体检不改数据"的证明）
function Fingerprint([string]$DbName) {
    $tables = @('t_ctms_contract','t_ctms_contract_item','t_ctms_customer','t_ctms_supplier','t_ctms_product','t_ctms_change_log','t_ctms_attachment')
    $parts = @()
    foreach ($t in $tables) {
        $n = Q $DbName "select count(*) from $t"
        $h = Q $DbName "select ifnull(md5(group_concat(id order by id separator '|')),'-') from $t"
        $parts += "$t=$n/$h"
    }
    return ($parts -join ';')
}

$ddl      = Join-Path $SqlDir '二开-合同台账.sql'
$dictSql  = Join-Path $SqlDir '二开-合同台账-字典参数.sql'
$numSql   = Join-Path $SqlDir '二开-合同台账-编号配置.sql'
$menuSql  = Join-Path $SqlDir '二开-合同台账-菜单.sql'
$health   = Join-Path $SqlDir "体检-合同台账-$Date.sql"
$impact   = Join-Path $SqlDir "影响行数报告-合同台账-$Date.sql"
$rollback = Join-Path $SqlDir "回滚-合同台账-$Date.sql"
foreach ($f in @($ddl,$dictSql,$numSql,$menuSql,$health,$impact,$rollback)) {
    if (-not (Test-Path $f)) { throw "缺少交付物：$f" }
}

Step "A. 建一次性演练库并载入上游基线（table.sql + data.sql）"
Exec 'mysql' "DROP DATABASE IF EXISTS $DbName; CREATE DATABASE $DbName DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
$r = RunFile (Join-Path $SqlDir 'table.sql') $DbName
Assert-That (-not $r.err) 'table.sql 载入无错误'
Assert-That ((Q $DbName "select count(*) from information_schema.tables where table_schema='$DbName'") -gt 100) '基线表已就位（>100 张）'
# ⚠ 建表脚本头部明确要求：加 FK 引用 sys_user/sys_dept，回填段还用基线账号兜底 →
#   **data.sql 必须先于 B3 建表脚本执行**，否则父行不存在、加 FK 会报 1452
$r = RunFile (Join-Path $SqlDir 'data.sql') $DbName
Assert-That (-not $r.err) 'data.sql 载入无错误（父行 sys_user/sys_dept 已就位）'
$USERID = Q $DbName "select user_id from sys_user order by (user_id='superAdmin') desc, user_id limit 1"
$DEPTID = Q $DbName "select dept_id from sys_dept order by char_length(ifnull(ancestors,'')), dept_id limit 1"
Assert-That ($USERID -ne '' -and $DEPTID -ne '') "取到基线父行 id（user=$USERID / dept=$DEPTID）"

Step 'B. 跑 B3 建表脚本（第 1、2 次都要成功 = 幂等）'
$r1 = RunFile $ddl $DbName
Assert-That (-not $r1.err) '第 1 次执行无错误'
$r2 = RunFile $ddl $DbName
Assert-That (-not $r2.err) '第 2 次执行无错误（幂等）'
$cnt = Q $DbName "select count(*) from information_schema.tables where table_schema='$DbName' and table_name like 't_ctms\_%'"
Assert-That ($cnt -eq '13') "13 张业务表齐备（实际 $cnt）"
$fk = Q $DbName "select count(*) from information_schema.key_column_usage where constraint_schema='$DbName' and referenced_table_name is not null and (table_name like 't_ctms\_%' or referenced_table_name like 't_ctms\_%')"
Info "本变更集相关外键数量 = $fk"

Step 'C. 执行字典 / 编号 / 菜单种子（回滚要撤的东西）'
$r = RunFile $dictSql $DbName; Assert-That (-not $r.err) '字典与参数脚本执行无错误'
$r = RunFile $numSql  $DbName; Assert-That (-not $r.err) '编号配置脚本执行无错误'
$r = RunFile $menuSql $DbName; Assert-That (-not $r.err) '菜单脚本执行无错误'
$preCounts = @{
    dict_type = Q $DbName "select count(*) from sys_dict_type"
    dict_data = Q $DbName "select count(*) from sys_dict_data"
    config    = Q $DbName "select count(*) from sys_config"
    menu      = Q $DbName "select count(*) from sys_menu"
    code_conf = Q $DbName "select count(*) from t_code_config"
    code_rule = Q $DbName "select count(*) from t_code_config_rule"
}
Info ("种子完成后行数：dict_type=$($preCounts.dict_type) dict_data=$($preCounts.dict_data) config=$($preCounts.config) menu=$($preCounts.menu) code_conf=$($preCounts.code_conf) code_rule=$($preCounts.code_rule)")

Step 'D. §2.2 体检：造违例 → 报告必须逐列等于造数，且业务表零变化'
# 造数：把 13 列临时改回可空，并插入精确数量的违例行
$nulls = @(
    @('t_ctms_contract','dept_id',1), @('t_ctms_contract','create_id',1),
    @('t_ctms_contract_item','product_id',3), @('t_ctms_contract_item','product_code',3),
    @('t_ctms_contract_item','product_name',3),
    @('t_ctms_change_log','object_type',1), @('t_ctms_change_log','object_id',1),
    @('t_ctms_attachment','object_type',1), @('t_ctms_attachment','object_id',1),
    @('t_ctms_customer','create_id',2), @('t_ctms_supplier','short_name',2),
    @('t_ctms_supplier','create_id',2), @('t_ctms_product','create_id',1)
)
# 先准备被引用的父行，避免 FK 报错（create_id 必须是 data.sql 里真实存在的账号）
Exec $DbName "INSERT INTO t_ctms_customer (id, code, name, short_name, create_id) VALUES ('DRLC1','DRLC1','体检客户1','简1','$USERID'), ('DRLC2','DRLC2','体检客户2','简2','$USERID');"
Exec $DbName "INSERT INTO t_ctms_product_type (id, code, name, path, level) VALUES ('DRLPT1','DRLPT1','体检类型','/DRLPT1/',1);"
Exec $DbName "INSERT INTO t_ctms_uom (id, code, name, decimals) VALUES ('DRLUOM1','DRLUOM1','个',0);"
Exec $DbName "INSERT INTO t_ctms_product (id, code, name, product_type_id, uom_id, create_id) VALUES ('DRLP1','DRLP1','体检物料','DRLPT1','DRLUOM1','$USERID');"
Exec $DbName "INSERT INTO t_ctms_contract (id, contract_no, name, subject_matter, dept_id, create_id) VALUES ('DRLCT1','DRLCT1','体检合同','体检标的物','$DEPTID','$USERID');"

$colDdl = ''
foreach ($n in $nulls) {
    $t, $c, $k = $n
    $type = Q $DbName "select column_type from information_schema.columns where table_schema='$DbName' and table_name='$t' and column_name='$c'"
    $charset = Q $DbName "select ifnull(character_set_name,'') from information_schema.columns where table_schema='$DbName' and table_name='$t' and column_name='$c'"
    if (-not $type) { Bad "列不存在：$t.$c"; continue }
    $coll = Q $DbName "select ifnull(collation_name,'') from information_schema.columns where table_schema='$DbName' and table_name='$t' and column_name='$c'"
    $typeSql = $type
    if ($charset) { $typeSql += " CHARACTER SET $charset" }
    if ($coll)    { $typeSql += " COLLATE $coll" }
    $colDdl += "ALTER TABLE $t MODIFY COLUMN $c $typeSql NULL;`n"
}
# 逐表把目标列置空（用每表已有行的 id，保证"违例行数"可控）
$mk = @"
$colDdl
UPDATE t_ctms_contract SET dept_id=NULL WHERE id='DRLCT1';
UPDATE t_ctms_contract SET create_id=NULL WHERE id='DRLCT1';
UPDATE t_ctms_customer SET create_id=NULL WHERE id IN ('DRLC1','DRLC2');
UPDATE t_ctms_supplier SET short_name=NULL WHERE id IN ('DRLS1','DRLS2');
UPDATE t_ctms_supplier SET create_id=NULL WHERE id IN ('DRLS1','DRLS2');
UPDATE t_ctms_product SET create_id=NULL WHERE id='DRLP1';
UPDATE t_ctms_contract_item SET product_id=NULL, product_code=NULL, product_name=NULL WHERE id IN ('DRLI1','DRLI2','DRLI3');
UPDATE t_ctms_change_log SET object_type=NULL, object_id=NULL WHERE id='DRLL1';
UPDATE t_ctms_attachment SET object_type=NULL, object_id=NULL WHERE id='DRLA1';
"@
$extra = @"
INSERT INTO t_ctms_supplier (id, code, name, short_name, create_id) VALUES ('DRLS1','DRLS1','体检供应商1','简1','$USERID'), ('DRLS2','DRLS2','体检供应商2','简2','$USERID');
INSERT INTO t_ctms_contract_item (id, contract_id, seq, item_type, name, spec, qty, unit_price, total, product_id, product_code, product_name) VALUES
 ('DRLI1','DRLCT1',1,'采购','行项1','',1,1,1,'DRLP1','DRLP1','体检物料'),
 ('DRLI2','DRLCT1',2,'采购','行项2','',1,1,1,'DRLP1','DRLP1','体检物料'),
 ('DRLI3','DRLCT1',3,'采购','行项3','',1,1,1,'DRLP1','DRLP1','体检物料');
INSERT INTO t_ctms_change_log (id, contract_id, field_name, old_value, new_value, source, object_type, object_id) VALUES ('DRLL1','DRLCT1','name','a','b','manual','contract','DRLCT1');
INSERT INTO t_ctms_attachment (id, contract_id, file_name, stored_path, size_bytes, object_type, object_id, del_flag) VALUES ('DRLA1','DRLCT1','a.pdf','/profile/a.pdf',1,'contract','DRLCT1','0');
$mk
"@
$r = Script $DbName $extra
Assert-That (-not $r.err) '违例造数完成（13 列已临时改回可空并置 NULL）'

$fpBefore = Fingerprint $DbName
$h = RunFile $health $DbName
Assert-That (-not $h.err) '体检脚本执行无错误'
$fpAfter = Fingerprint $DbName
Assert-That ($fpBefore -eq $fpAfter) '体检脚本执行前后业务表数据零变化（指纹一致）'

# 逐列核对体检结论
$mismatch = @()
foreach ($n in $nulls) {
    $t, $c, $expected = $n
    # RunFile 不带 --table → mysql 输出是 TAB 分隔：表名 \t 列名 \t 违例行数 \t 状态 \t 收紧目标
    $got = ($h.text -split "`r?`n") | Where-Object { $_ -like "$t`t$c`t*" } | Select-Object -First 1
    if ($got) { $actual = [int](($got -split "`t")[2]) } else { $actual = -1 }
    if ($actual -ne $expected) { $mismatch += "$t.$c 期望 $expected 实际 $actual" }
}
Assert-That ($mismatch.Count -eq 0) ("体检违例行数与造数逐列一致（13 列）" + $(if ($mismatch.Count) { ' → ' + ($mismatch -join '; ') } else { '' }))

Step 'E. §2.3 影响行数报告 + 快照'
$i = RunFile $impact $DbName
Assert-That (-not $i.err) '影响行数报告执行无错误'
$snapOk = $true
foreach ($t in @('t_ctms_contract','t_ctms_contract_item','t_ctms_customer')) {
    $src = Q $DbName "select count(*) from $t"
    $dst = Q $DbName "select count(*) from ${t}_bak_$Date"
    if ($src -ne $dst) { $snapOk = $false; Bad "快照行数不一致：$t 源=$src 快照=$dst" }
}
Assert-That $snapOk '新建表快照行数与源表一致'
foreach ($t in @('sys_menu','sys_dict_data','sys_dict_type','t_code_config','t_code_config_rule','sys_config')) {
    $src = Q $DbName "select count(*) from $t where 1=1"
    $dst = Q $DbName "select count(*) from ${t}_bak_$Date"
    Assert-That ([int]$dst -ge 1) "$t 快照已生成（快照 $dst 行 / 源表 $src 行）"
}

Step 'F. §2.5 回滚·中止守卫：删掉快照后必须拒绝回滚'
Exec $DbName "DROP TABLE IF EXISTS t_ctms_contract_bak_$Date;"
$rb = RunFile $rollback $DbName -AllowError
Assert-That ($rb.err) '快照缺失时回滚被拒绝（脚本报错中止）'
Assert-That ($rb.text -match 'B3_ROLLBACK_ABORTED_MISSING_SNAPSHOT') '错误原因是守卫表（缺快照），不是别的错'
Assert-That ((Q $DbName "select count(*) from information_schema.tables where table_schema='$DbName' and table_name='t_ctms_contract'") -eq '1') '中止后 t_ctms_contract 仍在（没有回滚一半）'

# 13 张业务表的精确清单（注意：**不能**用 `like 't_ctms\_%'` 统计"还剩几张表" ——
# 那会把 `t_ctms_*_bak_<日期>` 快照表一起数进来，实测因此误判"回滚没生效"。）
$ctmsList = "'t_ctms_contract','t_ctms_contract_item','t_ctms_tag','t_ctms_contract_tag','t_ctms_change_log','t_ctms_attachment','t_ctms_customer','t_ctms_supplier','t_ctms_party_draft','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse','t_ctms_product'"
$ctmsIn = "table_name in ($ctmsList)"
$fkCond = "(table_name in ($ctmsList) or referenced_table_name in ($ctmsList))"

Step 'G. §2.5 完整回滚（快照为"装完之后"拍的，故回滚到快照时刻）'
$r = RunFile $impact $DbName
Assert-That (-not $r.err) '重新生成快照成功'
$rb2 = RunFile $rollback $DbName
Assert-That (-not $rb2.err) '完整回滚执行无错误'
$left = Q $DbName "select count(*) from information_schema.tables where table_schema='$DbName' and $ctmsIn"
Assert-That ($left -eq '0') "13 张业务表已全部删除（剩余 $left；快照表不计）"
Assert-That ((Q $DbName "select count(*) from information_schema.tables where table_schema='$DbName' and table_name like 't_ctms\_%_bak\_$Date'") -eq '13') '13 张快照表按约定保留（回滚凭据不删）'
$leftFk = Q $DbName "select count(*) from information_schema.key_column_usage where constraint_schema='$DbName' and referenced_table_name is not null and $fkCond"
Assert-That ($leftFk -eq '0') "相关外键已全部删除（剩余 $leftFk）"
Assert-That ((Q $DbName "select count(*) from information_schema.routines where routine_schema='$DbName' and routine_name like 'b3\_rb\_%'") -eq '0') '临时存储过程已全部清理'
# 快照是"装完之后"拍的，且**只装白名单那批行**（存量表不做整表快照）→
# 回滚后该表的"白名单行"必须与快照行数一致（= 恢复到快照时刻的那批行）。
# 「回到装之前」的语义由步骤 H（真实迁移路径：装前拍快照）证明。
$whitelist = [ordered]@{
    'sys_dict_type'      = "dict_type in ('item_types','contract_types','subjects','contract_statuses','arrival_statuses')"
    'sys_dict_data'      = "dict_type in ('item_types','contract_types','subjects','contract_statuses','arrival_statuses')"
    'sys_config'         = "config_key='warranty_window_days'"
    'sys_menu'           = "menu_id='9F2C0000000000000000000000000001' or perms like 'ctms:%'"
    't_code_config'      = "id='9F2C0000000000000000000000C001'"
    't_code_config_rule' = "config_id='9F2C0000000000000000000000C001'"
}
foreach ($t in $whitelist.Keys) {
    $now = Q $DbName "select count(*) from $t where $($whitelist[$t])"
    $snap = Q $DbName "select count(*) from ${t}_bak_$Date"
    Assert-That ($now -eq $snap) "$t 的白名单行已按快照恢复（现 $now 行 = 快照 $snap 行）"
}

Step 'H. 真实迁移路径：装之前拍快照 → 装 B3 → 回滚必须回到"装之前"'
$Db2 = "$DbName-migrate"
Exec 'mysql' "DROP DATABASE IF EXISTS ``$Db2``; CREATE DATABASE ``$Db2`` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
$r = RunFile (Join-Path $SqlDir 'table.sql') $Db2; Assert-That (-not $r.err) '[迁移库] table.sql 载入无错误'
$r = RunFile (Join-Path $SqlDir 'data.sql')  $Db2; Assert-That (-not $r.err) '[迁移库] data.sql 载入无错误'
# 装之前的状态基线（回滚后必须逐项相等）
$before = @{}
foreach ($t in @('sys_dict_type','sys_dict_data','sys_config','sys_menu','t_code_config','t_code_config_rule')) {
    $before[$t] = Q $Db2 "select count(*) from $t"
}
Info ("[迁移库] 装之前：dict_type=$($before.sys_dict_type) dict_data=$($before.sys_dict_data) config=$($before.sys_config) menu=$($before.sys_menu) code_conf=$($before.t_code_config) code_rule=$($before.t_code_config_rule)")

$r = RunFile $impact $Db2
Assert-That (-not $r.err) '[迁移库] 装之前跑影响行数报告无错误'
Assert-That (($r.text -split "`r?`n" | Select-String -Pattern '表不存在，跳过').Count -eq 13) '[迁移库] 13 张业务表在装之前被正确报为"表不存在，跳过"'
foreach ($t in @('sys_dict_type','sys_dict_data','sys_config','sys_menu','t_code_config','t_code_config_rule')) {
    Assert-That ((Q $Db2 "select count(*) from information_schema.tables where table_schema='$Db2' and table_name='${t}_bak_$Date'") -eq '1') "[迁移库] $t 的装前快照已生成"
}

$r = RunFile $ddl $Db2; Assert-That (-not $r.err) '[迁移库] 建表脚本执行无错误'
$r = RunFile $dictSql $Db2; Assert-That (-not $r.err) '[迁移库] 字典脚本执行无错误'
$r = RunFile $numSql  $Db2; Assert-That (-not $r.err) '[迁移库] 编号脚本执行无错误'
$r = RunFile $menuSql $Db2; Assert-That (-not $r.err) '[迁移库] 菜单脚本执行无错误'
Assert-That ((Q $Db2 "select count(*) from sys_menu where perms like 'ctms:%'") -eq '26') '[迁移库] B3 权限点已装好（26 个：第 6 组新增了 ctms:attachment:list）'

$rb3 = RunFile $rollback $Db2
Assert-That (-not $rb3.err) '[迁移库] 回滚执行无错误'
Assert-That ((Q $Db2 "select count(*) from information_schema.tables where table_schema='$Db2' and $ctmsIn") -eq '0') '[迁移库] 13 张业务表已删除'
Assert-That ((Q $Db2 "select count(*) from information_schema.key_column_usage where constraint_schema='$Db2' and referenced_table_name is not null and $fkCond") -eq '0') '[迁移库] 相关外键已删除'
Assert-That ((Q $Db2 "select count(*) from sys_menu where perms like 'ctms:%'") -eq '0') '[迁移库] ctms 权限点已清空'
Assert-That ((Q $Db2 "select count(*) from sys_dict_type where dict_type in ('item_types','contract_types','subjects','contract_statuses','arrival_statuses')") -eq '0') '[迁移库] B3 字典类型已清空'
Assert-That ((Q $Db2 "select count(*) from sys_config where config_key='warranty_window_days'") -eq '0') '[迁移库] B3 系统参数已清空'
Assert-That ((Q $Db2 "select count(*) from t_code_config where id='9F2C0000000000000000000000C001'") -eq '0') '[迁移库] B3 编号配置已清空'
$backOk = $true
foreach ($t in $before.Keys) {
    $now = Q $Db2 "select count(*) from $t"
    if ($now -ne $before[$t]) { $backOk = $false; Bad "[迁移库] $t 未回到装前状态：装前 $($before[$t]) 现在 $now" }
}
Assert-That $backOk '[迁移库] 六张存量表全部回到"装之前"的行数（按快照反向回填生效）'

Step 'I. §2.5 CONSTRAINTS_ONLY：只撤约束（表保留、FK 消失、13 列恢复可空）'
$r = RunFile $ddl $Db2
Assert-That (-not $r.err) '[迁移库] 重新建表成功'
$fkBefore = Q $Db2 "select count(*) from information_schema.key_column_usage where constraint_schema='$Db2' and referenced_table_name is not null and $fkCond"
Assert-That ([int]$fkBefore -gt 0) "[迁移库] 建表后有外键（$fkBefore 个）"
$rbText = (Get-Content -Raw -Encoding UTF8 $rollback).Replace("SET @phase    = 'BOTH';", "SET @phase    = 'CONSTRAINTS_ONLY';")
$tmpRb = Join-Path $env:TEMP "b3-rollback-constraints-only.sql"
[System.IO.File]::WriteAllText($tmpRb, $rbText, (New-Object System.Text.UTF8Encoding($false)))
$r = RunFile $tmpRb $Db2
Remove-Item $tmpRb -Force
Assert-That (-not $r.err) '[迁移库] CONSTRAINTS_ONLY 回滚执行无错误'
Assert-That ((Q $Db2 "select count(*) from information_schema.tables where table_schema='$Db2' and $ctmsIn") -eq '13') '[迁移库] 13 张表仍在（只撤约束）'
$fkAfter = Q $Db2 "select count(*) from information_schema.key_column_usage where constraint_schema='$Db2' and referenced_table_name is not null and $fkCond"
Assert-That ($fkAfter -eq '0') "[迁移库] 外键已全部撤掉（之前 $fkBefore 个，现在 $fkAfter 个）"
$nn = Q $Db2 "select count(*) from information_schema.columns where table_schema='$Db2' and ((table_name='t_ctms_contract' and column_name in ('dept_id','create_id')) or (table_name='t_ctms_contract_item' and column_name in ('product_id','product_code','product_name')) or (table_name='t_ctms_supplier' and column_name in ('short_name','create_id'))) and is_nullable='NO'"
Assert-That ($nn -eq '0') "[迁移库] 13 列已恢复可空（仍为 NOT NULL 的列数 = $nn）"

Step '收尾：DROP DATABASE（两个演练库）'
Exec 'mysql' "DROP DATABASE IF EXISTS $DbName; DROP DATABASE IF EXISTS ``$Db2``;"
Assert-That ((Q 'mysql' "select count(*) from information_schema.schemata where schema_name='$DbName'") -eq '0') "演练库 $DbName 已删除"
Assert-That ((Q 'mysql' "select count(*) from information_schema.schemata where schema_name='$Db2'") -eq '0') "迁移演练库 $Db2 已删除"

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) { $script:Fails | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }; exit 1 }
exit 0
