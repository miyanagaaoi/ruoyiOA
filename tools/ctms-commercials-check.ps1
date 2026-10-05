<#
================================================================================
 ctms-commercials-check.ps1 —— 合同台账 B3「商务要素、质保与编号」验收
                               （第 5 组 / 任务 5.1~5.8）
--------------------------------------------------------------------------------
 为什么是"真实环境 + 接口"：
   第 5 组的验收面全是**可观察的落库值与算法结果**：编号由服务端生成且按
   「类型码 + 主体码 + 年份」分桶（计数器在 Redis 里，内存桩证明不了）、
   金额先舍入再汇总、标的物摘要真正落库、质保到期日按算法算、质保提醒的窗口边界、
   付款比例。这些口径写错**不会报错**，只会让数据悄悄错掉 —— 只能靠真实环境断言。

 覆盖（对应 specs/ctms/contract-commercials/spec.md 的 8 个 Requirement）：
   5.1 行项校验与顺序重排、物料必填、物料编码/名称快照、只改物料绑定也要留痕（_items）
   5.2 C-1 金额口径：行总价 HALF_UP 到分，合同金额 = 各已舍入行总价之和
   5.3 标的物摘要真正落库（不只写日志）+ 金额/摘要自动来源留痕
   5.4 质保到期算法（服务端算，前端传值不参与）
   5.5 质保金额 ↔ 比例互换；关闭质保清空 7 个字段并逐个留痕
   5.6 质保提醒：即将到期 [今天, 今天+窗口] / 已到期 < 今天；释放后消失；停用不提醒；
       窗口随系统参数变化
   5.7 付款比例（不扣质保金；金额 0 返回空值）+ 累计已付超出金额可保存且留痕
   5.8 编号：格式、按「类型码+主体码+年份」分桶、跨年重置不按月重置、预览不占号、
       停用占号不复用、冲突提示、停用类型/主体非法被拒、登记时服务端生成

 用法（在仓库根目录，先跑 start-env.ps1 与 oa-login.ps1）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-commercials-check.ps1

 前置：后端 8080 在线；`.cache\token-superAdmin.txt` 有效；
       rad_oa 已执行 `sql\二开-合同台账.sql`、字典参数 SQL、`二开-合同台账-编号配置.sql`。

 副作用：夹具全部用 ASCII 前缀 CTMSAL / CCOM，收尾按白名单物理删除（幂等，可反复跑）。
         开头的 Clear-Fixtures 会清掉**上一轮**的残留，因此脚本可重复执行。
         参数用例会把 `warranty_window_days` 临时改成 1 再还原（finally 里保证还原）。

 ⚠ 编号相关断言的写法（照抄即可，别改）：
   1. **期望值必须自己按"今天/签订日期"算**：编号里的年份/月份码与"跨年"断言都依赖当前年份，
      所以期望值在脚本里现算，不要写死 `PURZC202609000001`。
   2. **序号的绝对起点不可假设**：Redis 计数器是跨轮持久的，所以所有涉及序号的断言都用
      "同一轮内前后两次调用"的方式（自洽），不去清 Redis（生产库上清 Redis 是危险动作）。
   3. **编号预览的返回值在 `msg` 上**（实测）：
      成功 `{"msg":"PURZC202609000001","code":200}`、失败 `{"msg":"该类型暂不支持自动编号","code":500}`。
      原因是 `AjaxResult.success(String)` 命中的是 `success(String msg)` 重载，
      值落在 `msg`（与 2.8 的 `/system/config/configKey` 同一个坑）。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl   = 'http://localhost:8080',
    [string]$MySqlCli  = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir  = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database  = 'rad_oa',
    # 编号的参考日期：年份/月份码取它（规格：月份码取签订日期，为空取当天）
    [string]$SignDate  = '2026-09-15',
    [string]$TypeCode  = 'PUR',
    [string]$Subject   = 'ZC',
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
    $tmp = Join-Path $env:TEMP ('ctmssal-' + [guid]::NewGuid().ToString('N') + '.sql')
    # ⚠ 含中文的 SQL 必须走 stdin（DEV-ENV §6.33），且写出时用 UTF-8 无 BOM
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
function IsOk($res)  { return ($null -ne $res) -and ($null -ne $res.code) -and ($res.code -eq 200) }
function RowsOf($res) { if ($res -and $res.rows) { return @($res.rows) } return @() }
function PreviewNo([string]$type, [string]$subject, [string]$referenceDay) {
    $q = "/ctms/contract/next-no?type=$([uri]::EscapeDataString($type))" +
         "&subjectCode=$([uri]::EscapeDataString($subject))"
    if ($referenceDay) { $q += "&referenceDay=$([uri]::EscapeDataString($referenceDay))" }
    return (Api 'GET' $q $null)
}
function PreviewValue($res) { if (IsOk $res) { return (MsgOf $res) } return '' }
function NoOf([string]$name) { return (Sql "SELECT contract_no FROM t_ctms_contract WHERE name='$name'") }
function IdOf([string]$name) { return (Sql "SELECT id FROM t_ctms_contract WHERE name='$name'") }

# ================================================================ 夹具标识
$S = (Get-Date -Format 'HHmmss') + (Get-Random -Minimum 100 -Maximum 999)
$CustId  = "CCOMCUST$S"
$TypeId  = "CCOMTYPE$S"
$UomId   = "CCOMUOM$S"
$ProdId  = "CCOMPROD$S"      # 规格 DN50、编码 CCOMP$S
$Prod2Id = "CCOMPROD2$S"     # 第二个物料（测"只改物料绑定"），规格 M12、编码 CCOMP2$S

<#
 清理判据（三种都要覆盖，理由同 ctms-contract-check.ps1）：
   · 大多数夹具是服务端生成的 UUID 主键，只能按 name 前缀找；
   · 手工编号夹具按 contract_no 前缀找；
   · 上一轮异常退出时残留的行名字里带的是**上一轮**的序号，所以这里按固定前缀清。
#>
function Clear-Fixtures {
    $out = SqlFile @"
-- ⚠ 清理顺序铁律：产品被 t_ctms_contract_item.product_id 用 FK 引用（fk_contract_item_product），
--   客户被 t_ctms_contract.customer_id 用 FK 引用（fk_contract_customer）。
--   所以"删夹具对象"必须排在"删引用它的行"之后；否则上一轮异常退出的残留会让本轮
--   撞 1451 而整脚本中止，并把 LockFile 留在盘上（下一次运行被自己的锁拦下）。
--   另外这里按 customer_id 反查一次"上一轮残留的合同"，不只看本轮序号。
DELETE FROM t_ctms_contract_tag WHERE contract_id IN
    (SELECT id FROM t_ctms_contract WHERE name LIKE 'CTMSAL%' OR contract_no LIKE 'CTMSAL%' OR customer_id LIKE 'CCOMCUST%');
DELETE FROM t_ctms_change_log WHERE object_type='contract' AND object_id IN
    (SELECT id FROM t_ctms_contract WHERE name LIKE 'CTMSAL%' OR contract_no LIKE 'CTMSAL%' OR customer_id LIKE 'CCOMCUST%');
DELETE FROM t_ctms_contract_item WHERE contract_id IN
    (SELECT id FROM t_ctms_contract WHERE name LIKE 'CTMSAL%' OR contract_no LIKE 'CTMSAL%' OR customer_id LIKE 'CCOMCUST%');
-- 兜底：清掉任何仍然引用夹具产品的孤儿行项（跨脚本残留）
DELETE i FROM t_ctms_contract_item i JOIN t_ctms_product p ON p.id = i.product_id
 WHERE p.id LIKE 'CCOM%' OR p.id LIKE 'CTM%';
DELETE FROM t_ctms_contract WHERE name LIKE 'CTMSAL%' OR contract_no LIKE 'CTMSAL%' OR customer_id LIKE 'CCOMCUST%';
DELETE FROM t_ctms_product WHERE id LIKE 'CCOMPROD%';
DELETE FROM t_ctms_uom WHERE id LIKE 'CCOMUOM%';
DELETE FROM t_ctms_product_type WHERE id LIKE 'CCOMTYPE%';
DELETE FROM t_ctms_customer WHERE id LIKE 'CCOMCUST%';
"@
    if ($out -match 'ERROR') {
        Bad "夹具清理出现 SQL 错误（按 FK 名定位依赖顺序）：$out"
    }
}

# ------------------------------------------------------------------ 运行锁
$script:LockFile = Join-Path $CacheDir 'ctms-commercials-check.lock'
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
$tables = @('t_ctms_contract','t_ctms_contract_item','t_ctms_change_log','t_ctms_product','t_code_config','t_code_config_rule')
$missing = @()
foreach ($t in $tables) {
    if ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$t'") -ne '1') { $missing += $t }
}
if ($missing.Count -gt 0) { throw "缺少依赖对象：$($missing -join ', ')" }
$confId = '9F2C0000000000000000000000C001'
Assert-That ((Sql "SELECT COUNT(*) FROM t_code_config WHERE id='$confId' AND enable_flag='1'") -eq '1') `
    '编号配置存在（sql\二开-合同台账-编号配置.sql 已执行）'
Assert-That ((Sql "SELECT COUNT(*) FROM t_code_config_rule WHERE config_id='$confId' AND del_flag='0'") -eq '5') `
    '编号规则 5 条（业务参数×2 + 年 + 月 + 6 位按年序号）'

$adminId = Sql "SELECT user_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
$todayDb = Sql 'SELECT CURDATE()'
$warrantyCfgId = Sql "SELECT config_id FROM sys_config WHERE config_key='warranty_window_days'"
$warrantyCfgName = Sql "SELECT config_name FROM sys_config WHERE config_key='warranty_window_days'"
$warrantyCfgType = Sql "SELECT config_type FROM sys_config WHERE config_key='warranty_window_days'"
$warrantyCfgValue = Sql "SELECT config_value FROM sys_config WHERE config_key='warranty_window_days'"
$windowDays = 30
if ($warrantyCfgValue -match '^\d+$') { $windowDays = [int]$warrantyCfgValue }
Assert-That ($warrantyCfgId -ne '' -and $warrantyCfgId -notmatch '^ERROR') `
    "质保提醒窗口参数存在（config_id=$warrantyCfgId，值=$warrantyCfgValue）"

$signYear = $SignDate.Substring(0, 4)
$signMonth = $SignDate.Substring(5, 2)
$nextYear = ([int]$signYear + 1).ToString()
Info "夹具序号 $S；签订日期=$SignDate；类型码=$TypeCode；主体码=$Subject"
Info "库内今天=$todayDb；质保提醒窗口=$windowDays 天；PowerShell 今天=$(Get-Date -Format 'yyyy-MM-dd')"
Assert-That ($todayDb -match '^\d{4}-\d{2}-\d{2}$') '能取到库内当天日期（提醒窗口断言的基准）'

Clear-Fixtures

try {
    # ============================================================ 夹具
    Step '准备夹具：客户 + 物料域（两个物料，规格 DN50 / M12）'
    $null = SqlFile @"
INSERT INTO t_ctms_customer (id,code,name,create_id,create_time,update_time) VALUES ('$CustId','CCOM-CUS-$S','验收客户$S','$adminId',NOW(),NOW());
INSERT INTO t_ctms_product_type (id,name,code,path,level,sort,enable_flag,create_time,update_time) VALUES ('$TypeId','验收类型$S','CCOMT$S','/',1,1,'1',NOW(),NOW());
INSERT INTO t_ctms_uom (id,code,name,decimals,enable_flag,create_time,update_time) VALUES ('$UomId','CCOMU$S','验收单位$S',3,'1',NOW(),NOW());
INSERT INTO t_ctms_product (id,code,name,spec,product_type_id,uom_id,default_price,enable_flag,create_id,create_time,update_time)
  VALUES ('$ProdId','CCOMP$S','验收球阀$S','DN50','$TypeId','$UomId',12.3456,'1','$adminId',NOW(),NOW());
INSERT INTO t_ctms_product (id,code,name,spec,product_type_id,uom_id,default_price,enable_flag,create_id,create_time,update_time)
  VALUES ('$Prod2Id','CCOMP2$S','验收螺栓$S','M12','$TypeId','$UomId',1.0000,'1','$adminId',NOW(),NOW());
"@
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_product WHERE id IN ('$ProdId','$Prod2Id')") -eq '2') `
        '夹具：客户 / 类型 / 单位 / 两个物料已就位'

    # ============================================================ 5.8 编号
    Step '5.8 编号：登记时留空编号 → 服务端按规则生成'
    $auto = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL自动编号甲$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; arrivalStatus = '未到货'; customerId = $CustId
    }
    Assert-That (IsOk $auto) "编号留空登记成功（msg=$(MsgOf $auto)）"
    $autoId = IdOf "CTMSAL自动编号甲$S"
    Assert-That ($autoId -ne '' -and $autoId -notmatch '^ERROR') "合同已落库（id=$autoId）"
    if ($autoId -eq '' -or $autoId -match '^ERROR') { throw "自动编号夹具登记失败，后续断言依赖它。code=$($auto.code) msg=$(MsgOf $auto)" }
    $no1 = NoOf "CTMSAL自动编号甲$S"
    $noRe = "^$TypeCode$Subject$signYear${signMonth}\d{6}$"
    Assert-That ($no1 -match $noRe) "编号格式 = 3 位类型码 + 2 位主体码 + yyyy + MM + 6 位序号（实得 $no1，期望匹配 $noRe）"
    Assert-That ($no1.Length -eq 17) "编号长度 17（实得 $($no1.Length)）"
    $seq1 = [int]$no1.Substring(11)
    $bucket1 = $no1.Substring(0, 9)
    Info "编号 = $no1（序号 $seq1，桶 $bucket1；序号绝对起点取决于 Redis，故下面全部用前后对照断言）"

    Step '5.8 编号：同桶续号 / 月份不重置 / 换桶独立计数 / 跨年重置'
    # ---- 同桶：同类型码 + 同主体码 + 同年份，月份不同 → 序号 +1（月份不参与序号）
    $signDate2 = "$signYear-10-20"
    $auto2 = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL自动编号乙$S"; type = $TypeCode; subjectCode = $Subject; signDate = $signDate2
        status = '已签订'; arrivalStatus = '未到货'
    }
    Assert-That (IsOk $auto2) "同桶第二份登记成功（msg=$(MsgOf $auto2)）"
    $no2 = NoOf "CTMSAL自动编号乙$S"
    $prefix1 = $no1.Substring(0, 9)
    $seq1 = [int]$no1.Substring(11)
    $seq2 = [int]$no2.Substring(11)
    Assert-That ($no2.StartsWith($prefix1)) `
        "同类型码 + 同主体码 + 同年份 → 同一前缀 $prefix1（实得 $no2）"
    Assert-That ($no2.Substring(9, 2) -eq '10') "月份码取签订日期的月份（实得 $($no2.Substring(9,2))）"
    Assert-That ($seq2 -eq ($seq1 + 1)) "同年前缀下序号 +1：$seq1 → $seq2（月份不重置序号）"

    # ---- 换主体码 → 另一个桶（独立计数，不影响上面那个桶）
    $auto3 = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL换主体$S"; type = $TypeCode; subjectCode = 'YX'; signDate = $SignDate
        status = '已签订'; arrivalStatus = '未到货'
    }
    Assert-That (IsOk $auto3) "换主体码登记成功（msg=$(MsgOf $auto3)）"
    $no3 = NoOf "CTMSAL换主体$S"
    Assert-That ($no3 -match "^${TypeCode}YX$signYear${signMonth}\d{6}$") `
        "主体码进入编号且不影响原主体桶（实得 $no3）"

    # ---- 换类型码 → 另一个桶
    $auto4 = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL换类型$S"; type = 'SAL'; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; arrivalStatus = '未到货'
    }
    Assert-That (IsOk $auto4) "换类型码登记成功（msg=$(MsgOf $auto4)）"
    $no4 = NoOf "CTMSAL换类型$S"
    Assert-That ($no4 -match "^SAL$Subject$signYear${signMonth}\d{6}$") `
        "类型码进入编号且不影响原类型桶（实得 $no4）"

    # ---- 跨年重置：换成"次年的年份"就是一个全新的计数器桶
    # ⚠ 这里刻意**不**断言"序号 = 000001"：Redis 计数器跨轮持久，复跑同一个桶就会有旧值，
    #   写死 000001 会得到"复跑即假失败"的脚本。真正的证据是下面两条：
    #   ① 跨年桶按**自身**递增（连续两份 +1，而不是接着旧年份桶的序号走）；
    #   ② 跨年桶的序号 **远小于** 旧年份桶的序号（说明它没有继承旧年份桶的计数）。
    #   ⇒ 与"1 月 1 日当天有没有定时任务执行"完全无关（这条是 §1.2 判定 ② 的落点）。
    $crossYear = $nextYear
    $crossName1 = "CTMSAL跨年首号$S"
    $auto5a = Api 'POST' '/ctms/contract' @{
        name = $crossName1; type = $TypeCode; subjectCode = $Subject; signDate = "$crossYear-01-05"
        status = '已签订'; arrivalStatus = '未到货'
    }
    Assert-That (IsOk $auto5a) "次年签订日期的合同登记成功（msg=$(MsgOf $auto5a)）"
    $no5a = NoOf $crossName1
    $crossPrefix = "${TypeCode}${Subject}${crossYear}01"
    Assert-That ($no5a -match "^$crossPrefix\d{6}$") `
        "年份码取签订日期的年份、月份码取签订日期的月份（实得 $no5a）"
    $crossSeq1 = [int]$no5a.Substring(11)

    $crossName2 = "CTMSAL跨年二号$S"
    $auto5b = Api 'POST' '/ctms/contract' @{
        name = $crossName2; type = $TypeCode; subjectCode = $Subject; signDate = "$crossYear-01-06"
        status = '已签订'; arrivalStatus = '未到货'
    }
    Assert-That (IsOk $auto5b) "次年桶第二份登记成功（msg=$(MsgOf $auto5b)）"
    $no5b = NoOf $crossName2
    $crossSeq2 = [int]$no5b.Substring(11)
    Assert-That ($crossSeq2 -eq ($crossSeq1 + 1)) "次年桶按自身计数递增：$crossSeq1 → $crossSeq2"
    Assert-That ($crossSeq1 -lt $seq1) `
        "跨年桶与旧年份桶相互独立：跨年桶序号 $crossSeq1 小于旧年份桶序号 $seq1（确实按年换桶、没有继承计数）"

    # ---- 回到旧年份桶：不受跨年影响，继续递增
    $auto6 = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL回到旧年$S"; type = $TypeCode; subjectCode = $Subject; signDate = "$signYear-11-01"
        status = '已签订'; arrivalStatus = '未到货'
    }
    Assert-That (IsOk $auto6) "回到旧年份登记成功（msg=$(MsgOf $auto6)）"
    $no6 = NoOf "CTMSAL回到旧年$S"
    $seq6 = [int]$no6.Substring(11)
    Assert-That ($no6.StartsWith($prefix1)) "回到旧年份仍落回原来的桶 $prefix1（实得 $no6）"
    Assert-That ($seq6 -eq ($seq2 + 1)) `
        "跨年换桶不影响旧年份桶：$seq2 → $seq6（旧年份桶没有被跨年重置）"

    # ---- 预览不占号：在一个"脚本本轮没动过"的独立桶上验证（FIN + 本主体 + 今年）
    Step '5.8 编号：预览不占号 + 真正取号拿到预览值'
    $previewPrefix = "FIN${Subject}${signYear}${signMonth}"
    $p1 = PreviewValue (PreviewNo 'FIN' $Subject $SignDate)
    $p2 = PreviewValue (PreviewNo 'FIN' $Subject $SignDate)
    Assert-That ($p1 -ne '' -and $p1 -eq $p2) "连续两次预览返回同一编号（$p1）"
    Assert-That ($p1 -match "^$previewPrefix\d{6}$") "预览编号格式与桶正确（实得 $p1）"
    $previewSeq = [int]$p1.Substring(11)
    $takenCount = [int](Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE contract_no LIKE '$previewPrefix%'")
    Assert-That ($previewSeq -gt $takenCount) `
        "预览不占号：预览序号 $previewSeq 大于该桶已占用编号数 $takenCount（预览没有把号写进库）"
    Assert-That ($p1 -ne $no1 -and $p1 -ne $no6 -and $p1 -ne $no5b) "预览值不是任何已用编号"

    $auto7 = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL预览后登记$S"; type = 'FIN'; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; arrivalStatus = '未到货'
    }
    Assert-That (IsOk $auto7) "预览后登记成功（msg=$(MsgOf $auto7)）"
    $no7 = NoOf "CTMSAL预览后登记$S"
    Assert-That ($no7 -eq $p1) "预览不占号：真正取号拿到的正是刚才预览的值（实得 $no7，预览 $p1）"

    Step '5.8 编号：类型/主体取值校验（预览与登记同一套口径）'
    $r = PreviewNo 'OTH' $Subject $SignDate
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '该类型暂不支持自动编号') `
        "不参与自动编号的类型预览被拒（msg=$(MsgOf $r)）"
    $r = PreviewNo $TypeCode '' $SignDate
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '请选择有效主体') "主体为空被拒（msg=$(MsgOf $r)）"
    $r = PreviewNo $TypeCode 'ZZ' $SignDate
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '请选择有效主体') "主体不在配置清单内被拒（msg=$(MsgOf $r)）"
    $r = PreviewNo $TypeCode $Subject '2026/09/15'
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '参考日期格式错误') "参考日期格式非法被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{ name = "CTMSAL停用类型$S"; type = 'OTH'; subjectCode = $Subject; signDate = $SignDate }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '该类型暂不支持自动编号') `
        "登记路径同样拒绝不参与编号的类型（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE name='CTMSAL停用类型$S'") -eq '0') '被拒的登记没有落库'

    Step '5.8 编号：停用占号不复用 + 已占用编号冲突提示'
    # 用刚登记的「次年桶首号」合同来证明"停用占号不复用"：
    # 它占有 $no5a，被停用之后这个编号仍然不得被再次分配。
    $crossId = IdOf $crossName1
    Assert-That ($crossId -ne '' -and $crossId -notmatch '^ERROR') "取到次年桶首号合同的主键（id=$crossId）"
    $r = Api 'DELETE' "/ctms/contract/$crossId`?reason=%E9%AA%8C%E6%94%B6%E5%81%9C%E7%94%A8%E5%8D%A0%E5%8F%B7" $null
    Assert-That (IsOk $r) "该合同已停用（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_contract WHERE id='$crossId'") -eq '1') '停用标志已置 1（它仍然占号）'
    $r = Api 'POST' '/ctms/contract' @{ contractNo = $no5a; name = "CTMSAL复用占号甲$S"; type = $TypeCode; subjectCode = $Subject }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编号已存在') `
        "复用**被停用合同**占用的编号被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{ contractNo = $no1; name = "CTMSAL复用占号乙$S"; type = $TypeCode; subjectCode = $Subject }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编号已存在') "复用未停用合同的编号被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE name LIKE 'CTMSAL复用占号%$S'") -eq '0') '被拒的冲突登记没有落库'

    Step '5.8 编号：手工/历史编号通道（只校验格式 + 唯一性）'
    $manualNo = "${TypeCode}${Subject}${signYear}${signMonth}900001"
    $r = Api 'POST' '/ctms/contract' @{ contractNo = 'CTMSAL001'; name = "CTMSAL手工编号非法$S"; type = $TypeCode }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编号格式不正确') `
        "手工编号格式非法被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{ contractNo = $manualNo; name = "CTMSAL手工编号合法$S"; type = 'OTH'; subjectCode = $Subject }
    Assert-That (IsOk $r) "历史/导入通道允许手工编号（含不参与自动编号的类型 OTH，msg=$(MsgOf $r)）"
    Assert-That ((NoOf "CTMSAL手工编号合法$S") -eq $manualNo) '手工编号按提交值落库（历史数据必须保持原编号）'
    $r = Api 'POST' '/ctms/contract' @{ contractNo = $manualNo; name = "CTMSAL手工编号重复$S"; type = $TypeCode }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编号已存在') "手工编号重复被拒（msg=$(MsgOf $r)）"

    # ============================================================ 5.1 / 5.2 / 5.3
    Step '5.2 金额口径 C-1：行总价先 HALF_UP 到分，合同金额 = 各已舍入行总价之和'
    $money = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL金额口径$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; arrivalStatus = '未到货'; paidAmount = 0
        items = @(
            @{ productId = $ProdId; qty = 3;     unitPrice = 1.665; itemType = '销售' },
            @{ productId = $ProdId; qty = 3.333; unitPrice = 0.03;  itemType = '销售' }
        )
    }
    Assert-That (IsOk $money) "含 2 个行项的合同登记成功（msg=$(MsgOf $money)）"
    $moneyId = IdOf "CTMSAL金额口径$S"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_item WHERE contract_id='$moneyId'") -eq '2') '行项随主表一起落库'
    $totals = Sql "SELECT GROUP_CONCAT(total ORDER BY seq) FROM t_ctms_contract_item WHERE contract_id='$moneyId'"
    Assert-That ($totals -eq '5.00,0.10') "逐行 HALF_UP 到分：3×1.665→5.00、3.333×0.03→0.10（实得 $totals）"
    $amount = Sql "SELECT amount FROM t_ctms_contract WHERE id='$moneyId'"
    Assert-That ($amount -eq '5.10') "合同金额 = 各已舍入行总价之和（实得 $amount）"

    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL先舍入再汇总$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate; status = '已签订'
        items = @(
            @{ productId = $ProdId; qty = 1; unitPrice = 0.005; itemType = '销售' },
            @{ productId = $ProdId; qty = 1; unitPrice = 0.005; itemType = '销售' },
            @{ productId = $ProdId; qty = 1; unitPrice = 0.005; itemType = '销售' }
        )
    }
    Assert-That (IsOk $r) "「先舍入再汇总」判别用例已建（msg=$(MsgOf $r)）"
    $discAmount = Sql "SELECT amount FROM t_ctms_contract WHERE name='CTMSAL先舍入再汇总$S'"
    Assert-That ($discAmount -eq '0.03') "3 行 0.005 逐行舍入后汇总 = 0.03（先汇总再舍入会得 0.02；实得 $discAmount）"

    Step '5.1 行项：物料必填/非负/顺序重排/名称规格回落/物料快照'
    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL行项回落$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate; status = '已签订'
        items = @(
            @{ productId = $Prod2Id; qty = 10; unitPrice = 2.0000; itemType = '销售' },
            @{ productId = $ProdId;  qty = 2;  unitPrice = 3.0000; itemType = '销售' }
        )
    }
    Assert-That (IsOk $r) "两个行项登记成功（msg=$(MsgOf $r)）"
    $fallbackId = IdOf "CTMSAL行项回落$S"
    $joined = Sql "SELECT GROUP_CONCAT(CONCAT(seq,':',name,'/',spec,'/',product_code) ORDER BY seq SEPARATOR '|') FROM t_ctms_contract_item WHERE contract_id='$fallbackId'"
    $expectJoined = "1:验收螺栓$S/M12/CCOMP2$S|2:验收球阀$S/DN50/CCOMP$S"
    Assert-That ($joined -eq $expectJoined) `
        "序号按提交顺序 1..N、名称/规格回落物料档案、物料编码快照落库（实得 $joined）"
    $summ = Sql "SELECT subject_matter FROM t_ctms_contract WHERE id='$fallbackId'"
    Assert-That ($summ -eq "验收螺栓$S(M12)×10；验收球阀$S(DN50)×2") `
        "5.3 标的物摘要**真正落库**为「名称(规格)×数量」分号连接（实得 $summ）"
    $autoSummary = Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$fallbackId' AND field_name='_summary'"
    Assert-That ([int]$autoSummary -ge 1) '5.3 摘要改写留下变更历史（_summary）'

    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL缺物料$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        items = @(
            @{ productId = $ProdId; qty = 1; unitPrice = 1.0000 },
            @{ qty = 1; unitPrice = 1.0000 }
        )
    }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '行项第 2 行必须选择物料档案') `
        "未选物料被拒且报出重排后的行号（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE name='CTMSAL缺物料$S'") -eq '0') '整单不落库'
    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL负单价$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        items = @(@{ productId = $ProdId; qty = 1; unitPrice = -1.0000 })
    }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '不能为负数') "单价为负被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL负数量$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        items = @(@{ productId = $ProdId; qty = -1; unitPrice = 1.0000 })
    }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '不能为负数') "数量为负被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL物料不存在$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        items = @(@{ productId = 'NOT-EXIST-PRODUCT'; qty = 1; unitPrice = 1.0000 })
    }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '物料档案不存在') "物料档案不存在被拒（msg=$(MsgOf $r)）"

    Step '5.1 只改物料绑定的编辑也要留痕（_items 变更，金额按新行项重算）'
    $r = Api 'PUT' '/ctms/contract' @{
        id = $fallbackId; items = @(
            @{ productId = $ProdId; qty = 10; unitPrice = 2.0000; itemType = '销售' },
            @{ productId = $ProdId; qty = 2;  unitPrice = 3.0000; itemType = '销售' }
        )
    }
    Assert-That (IsOk $r) "把第 1 行物料换掉（数量/单价不变）编辑成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract_item WHERE contract_id='$fallbackId' AND product_id='$Prod2Id'") -eq '0') `
        '原物料绑定已被替换（行项全量替换）'
    $logCount = Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$fallbackId' AND field_name='_items'"
    Assert-That ([int]$logCount -ge 1) "只改物料绑定也留下 _items 变更历史（实得 $logCount 条；签名含物料标识）"
    $amountAfter = Sql "SELECT amount FROM t_ctms_contract WHERE id='$fallbackId'"
    Assert-That ($amountAfter -eq '26.00') "换绑定后金额按新行项重算（10×2.00 + 2×3.00 = 26.00，实得 $amountAfter）"
    $autoAmt = Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$fallbackId' AND field_name='合同金额' AND source='auto'"
    Assert-That ([int]$autoAmt -ge 1) '5.3 行项改写的金额留痕来源为 auto'

    # ============================================================ 5.4 / 5.5
    Step '5.4 质保到期算法：新增路径服务端算，前端传值不参与'
    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL质保算法$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; amount = 100000.00; hasWarranty = '1'
        warrantyStart = '2025-06-01'; warrantyMonths = 12
        # 前端**故意**传一个错的到期日：服务端必须无视它（这是 5.4 的关键断言）
        warrantyEnd = '2099-12-31'
    }
    Assert-That (IsOk $r) "启用质保的合同登记成功（msg=$(MsgOf $r)）"
    $warId = IdOf "CTMSAL质保算法$S"
    Assert-That ((Sql "SELECT warranty_end FROM t_ctms_contract WHERE id='$warId'") -eq '2026-05-31') `
        '到期日 = 2025-06-01 + (12-1) 月 → 该月最后一天 = 2026-05-31（前端传的 2099-12-31 被忽略）'
    Assert-That ([int](Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$warId' AND field_name='质量保证金到期日' AND source='auto'") -ge 1) `
        '到期日留痕来源为 auto'

    Step '5.5 质保金额 ↔ 比例互换'
    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL比例补金额$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; amount = 100000.00; hasWarranty = '1'
        warrantyStart = "$signYear-06-01"; warrantyMonths = 12; warrantyRate = 5
    }
    Assert-That (IsOk $r) "只填比例登记成功（msg=$(MsgOf $r)）"
    $rateId = IdOf "CTMSAL比例补金额$S"
    Assert-That ((Sql "SELECT warranty_amount FROM t_ctms_contract WHERE id='$rateId'") -eq '5000.00') `
        '100000.00 × 5% = 5000.00（只填比例 → 自动补金额）'
    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL金额补比例$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; amount = 30000.00; hasWarranty = '1'
        warrantyStart = "$signYear-06-01"; warrantyMonths = 12; warrantyAmount = 3000.00
    }
    Assert-That (IsOk $r) "只填金额登记成功（msg=$(MsgOf $r)）"
    $amtId = IdOf "CTMSAL金额补比例$S"
    Assert-That ((Sql "SELECT warranty_rate FROM t_ctms_contract WHERE id='$amtId'") -eq '10.0000') `
        '3000.00 ÷ 30000.00 × 100 = 10.0000（4 位；只填金额 → 自动补比例）'

    Step '5.5 关闭质保：清空 7 个字段并逐个留痕'
    # 先把 7 个字段都填上（用 SQL 直填，避免被"只填一个补另一个"的互换逻辑改写）
    $null = SqlFile @"
UPDATE t_ctms_contract
   SET warranty_amount = 5000.00, warranty_rate = 5.0000, warranty_start = '2026-06-01',
       warranty_months = 12, warranty_end = '2027-05-31', warranty_released = '1',
       warranty_release_date = '2026-07-01', has_warranty = '1'
 WHERE id = '$warId';
"@
    $filled = Sql "SELECT CONCAT(IFNULL(warranty_amount,'-'),'/',IFNULL(warranty_rate,'-'),'/',IFNULL(warranty_start,'-'),'/',IFNULL(warranty_months,'-'),'/',IFNULL(warranty_end,'-'),'/',IFNULL(warranty_released,'-'),'/',IFNULL(warranty_release_date,'-')) FROM t_ctms_contract WHERE id='$warId'"
    Assert-That ($filled -notmatch '/-') "夹具：7 个质保字段已全部填上（$filled）"
    $logsBefore = [int](Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$warId'")
    $r = Api 'PUT' '/ctms/contract' @{ id = $warId; hasWarranty = '0' }
    Assert-That (IsOk $r) "关闭质保编辑成功（msg=$(MsgOf $r)）"
    $cleared = Sql "SELECT CONCAT(IFNULL(warranty_amount,'null'),'/',IFNULL(warranty_rate,'null'),'/',IFNULL(warranty_start,'null'),'/',IFNULL(warranty_months,'null'),'/',IFNULL(warranty_end,'null'),'/',IFNULL(warranty_released,'null'),'/',IFNULL(warranty_release_date,'null')) FROM t_ctms_contract WHERE id='$warId'"
    Assert-That ($cleared -eq 'null/null/null/null/null/0/null') `
        "7 个质保字段全部清空、质保开关归 0（实得 $cleared）"
    $logsAfter = [int](Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$warId'")
    Assert-That (($logsAfter - $logsBefore) -ge 7) `
        "关闭质保新增 ≥7 条变更历史（逐个被清空字段留痕：$logsBefore → $logsAfter）"

    # ============================================================ 5.6 质保提醒
    Step '5.6 质保提醒：窗口边界（今天 + 窗口）/ 已到期 / 释放后消失 / 停用不提醒'
    function New-WarrantyFixture([string]$name, [int]$endOffsetDays, [string]$released = '0') {
        $r = Api 'POST' '/ctms/contract' @{
            name = $name; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
            status = '已签订'; arrivalStatus = '未到货'; amount = 100.00
            hasWarranty = '1'; warrantyStart = "$signYear-01-01"; warrantyMonths = 12
        }
        if (-not (IsOk $r)) { throw "质保提醒夹具登记失败：$name（code=$($r.code) msg=$(MsgOf $r)）" }
        $id = IdOf $name
        # 到期日直接按"今天 + N 天"写库：提醒判定的基准是库内当前日期，比用算法反推更精确
        $null = SqlFile "UPDATE t_ctms_contract SET warranty_end = DATE_ADD(CURDATE(), INTERVAL $endOffsetDays DAY), warranty_released='$released' WHERE id='$id';"
        return $id
    }
    $fixBoundary = New-WarrantyFixture "CTMSAL提醒边界$S" $windowDays          # 窗口右端点（含）
    $fixInside   = New-WarrantyFixture "CTMSAL提醒窗口内$S" ($windowDays - 1)  # 窗口内
    $fixExpired  = New-WarrantyFixture "CTMSAL提醒已到期$S" (-1)               # 已到期
    $fixReleased = New-WarrantyFixture "CTMSAL提醒已释放$S" ($windowDays - 1) '1'
    $fixDisabled = New-WarrantyFixture "CTMSAL提醒已停用$S" ($windowDays - 1)
    $r = Api 'DELETE' "/ctms/contract/$fixDisabled`?reason=%E9%AA%8C%E6%94%B6%E5%B7%B2%E5%81%9C%E7%94%A8" $null
    Assert-That (IsOk $r) "提醒夹具中的一份已停用（msg=$(MsgOf $r)）"

    $rem = Api 'GET' '/ctms/contract/warranty-reminders'
    Assert-That (IsOk $rem) "质保提醒接口可用（msg=$(MsgOf $rem)）"
    Assert-That ([int]$rem.data.windowDays -eq $windowDays) "返回的窗口天数取自 sys_config（$($rem.data.windowDays)）"
    Assert-That ([string]$rem.data.today -eq $todayDb) "返回的今天与库内当天一致（$($rem.data.today)）"
    $expiringIds = @($rem.data.expiring | ForEach-Object { $_.id })
    $expiredIds  = @($rem.data.expired  | ForEach-Object { $_.id })
    Assert-That ($expiringIds -contains $fixBoundary) "窗口右端点当天（今天 + $windowDays 天）落在「即将到期」（边界含）"
    Assert-That ($expiringIds -contains $fixInside) "窗口内的合同在「即将到期」"
    Assert-That ($expiredIds -contains $fixExpired) "到期日早于今天 → 在「已到期」"
    Assert-That (($expiringIds -contains $fixExpired) -eq $false) "已到期的合同**不**在「即将到期」（两个列表互斥）"
    Assert-That (($expiringIds -contains $fixReleased) -eq $false) "已释放质保 → 不在「即将到期」"
    Assert-That (($expiredIds -contains $fixReleased) -eq $false) "已释放质保 → 不在「已到期」"
    Assert-That (($expiringIds -contains $fixDisabled) -eq $false) "已停用 → 不在「即将到期」"
    Assert-That (($expiredIds -contains $fixDisabled) -eq $false) "已停用 → 不在「已到期」"
    Assert-That (@($rem.data.expiring).Count -le 100 -and @($rem.data.expired).Count -le 100) '两个列表均不超过 100 条（看板上限）'
    $endDates = @($rem.data.expiring | ForEach-Object { ([datetime]$_.warrantyEnd).ToString('yyyy-MM-dd') })
    $sorted = @($endDates | Sort-Object)
    Assert-That (($endDates -join ',') -eq ($sorted -join ',')) '「即将到期」按到期日升序'

    $r = Api 'PUT' "/ctms/contract/warranty/$fixInside/release" $null
    Assert-That (IsOk $r) "标记质保已释放成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT warranty_released FROM t_ctms_contract WHERE id='$fixInside'") -eq '1') '释放标志落库'
    Assert-That ((Sql "SELECT warranty_release_date IS NOT NULL FROM t_ctms_contract WHERE id='$fixInside'") -eq '1') '释放日期落库'
    $rem2 = Api 'GET' '/ctms/contract/warranty-reminders'
    $expiring2 = @($rem2.data.expiring | ForEach-Object { $_.id })
    $expired2  = @($rem2.data.expired  | ForEach-Object { $_.id })
    Assert-That (($expiring2 -contains $fixInside) -eq $false) '释放后立刻从「即将到期」消失'
    Assert-That (($expired2 -contains $fixInside) -eq $false) '释放后立刻从「已到期」消失'

    Step '5.6 提醒窗口天数随之系统参数变化（改参数 → 行为随之变化）'
    # ⚠ 必须走参数管理接口（PUT /system/config）而不是直接 UPDATE 表：
    #   SysConfigServiceImpl 把参数值缓存在 Redis（CacheConstants.SYS_CONFIG_KEY + key），
    #   直接改表不会刷新缓存，会得到"改了参数却没生效"的**假失败**。
    $r = Api 'PUT' '/system/config' @{
        configId = $warrantyCfgId; configKey = 'warranty_window_days'; configName = $warrantyCfgName
        configValue = '1'; configType = $warrantyCfgType; remark = 'ctms-commercials-check 临时改小'
    }
    Assert-That (IsOk $r) "把 warranty_window_days 改为 1（msg=$(MsgOf $r)）"
    try {
        $remNarrow = Api 'GET' '/ctms/contract/warranty-reminders'
        Assert-That ([int]$remNarrow.data.windowDays -eq 1) "窗口天数随参数变为 1（实得 $($remNarrow.data.windowDays)）"
        $narrowIds = @($remNarrow.data.expiring | ForEach-Object { $_.id })
        Assert-That (($narrowIds -contains $fixBoundary) -eq $false) `
            "窗口缩到 1 天后，「今天 + $windowDays 天」到期的不再出现在「即将到期」"
        Assert-That (($narrowIds -contains $fixExpired) -eq $false) "窗口缩小不会把已到期的算成即将到期"
    } finally {
        $r2 = Api 'PUT' '/system/config' @{
            configId = $warrantyCfgId; configKey = 'warranty_window_days'; configName = $warrantyCfgName
            configValue = "$windowDays"; configType = $warrantyCfgType; remark = '质保到期提醒窗口（天）'
        }
        Info "已还原 warranty_window_days = $windowDays（code=$($r2.code)）"
    }
    $remBack = Api 'GET' '/ctms/contract/warranty-reminders'
    Assert-That ([int]$remBack.data.windowDays -eq $windowDays) "参数已还原为 $windowDays 且再次生效"
    Assert-That ((Sql "SELECT config_value FROM sys_config WHERE config_key='warranty_window_days'") -eq "$windowDays") `
        '参数表里的值也已还原'

    # ============================================================ 5.7 付款比例
    Step '5.7 付款比例：不扣质保金；合同金额为空/0 返回空值；超出金额可保存且留痕'
    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL付款比例$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; amount = 200000.00; paidAmount = 50000.00
    }
    Assert-That (IsOk $r) "付款比例夹具登记成功（msg=$(MsgOf $r)）"
    $payId = IdOf "CTMSAL付款比例$S"
    $detail = Api 'GET' "/ctms/contract/$payId"
    Assert-That (IsOk $detail) "详情接口可用（msg=$(MsgOf $detail)）"
    Assert-That (([decimal]$detail.data.paidRate) -eq 25.0000) `
        "200000.00 与 50000.00 → 25.0000%（实得 $($detail.data.paidRate)）"
    $list = Api 'GET' "/ctms/contract/list?pageNum=1&pageSize=50&keyword=CTMSAL%E4%BB%98%E6%AC%BE%E6%AF%94%E4%BE%8B$S"
    $row = @(RowsOf $list | Where-Object { $_.id -eq $payId })
    Assert-That ($row.Count -eq 1 -and ([decimal]$row[0].paidRate) -eq 25.0000) '5.7 列表同样返回付款比例（派生列）'

    $r = Api 'POST' '/ctms/contract' @{
        name = "CTMSAL零金额比例$S"; type = $TypeCode; subjectCode = $Subject; signDate = $SignDate
        status = '已签订'; amount = 0.00; paidAmount = 0.00
    }
    Assert-That (IsOk $r) "零金额合同登记成功（msg=$(MsgOf $r)）"
    $zeroId = IdOf "CTMSAL零金额比例$S"
    $zeroDetail = Api 'GET' "/ctms/contract/$zeroId"
    Assert-That (IsOk $zeroDetail) '零金额合同的详情接口不报错'
    Assert-That ($null -eq $zeroDetail.data.paidRate) "合同金额为 0 时付款比例为空值（实得「$($zeroDetail.data.paidRate)」）"

    $r = Api 'PUT' '/ctms/contract' @{ id = $payId; paidAmount = 250000.00 }
    Assert-That (IsOk $r) "累计已付超出合同金额仍可保存（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT paid_amount FROM t_ctms_contract WHERE id='$payId'") -eq '250000.00') '超出金额已落库'
    Assert-That ([int](Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$payId' AND field_name='累计已付金额'") -ge 1) `
        '超出金额的编辑留下变更历史'
    Assert-That (([decimal](Api 'GET' "/ctms/contract/$payId").data.paidRate) -eq 125.0000) `
        '超出后付款比例 = 125.0000%（不截断、不报错）'

    $r = Api 'PUT' '/ctms/contract' @{
        id = $payId; amount = 100000.00; paidAmount = 50000.00; hasWarranty = '1'
        warrantyStart = "$signYear-06-01"; warrantyMonths = 12; warrantyAmount = 1000.00
    }
    Assert-That (IsOk $r) "加上 1000.00 质保金后编辑成功（msg=$(MsgOf $r)）"
    $withWarranty = [decimal](Api 'GET' "/ctms/contract/$payId").data.paidRate
    Assert-That ($withWarranty -eq 50.0000) `
        "付款比例不扣质保金：50000.00 ÷ 100000.00 = 50.0000%（实得 $withWarranty）"
}
finally {
    Step '收尾：删除全部夹具'
    Clear-Fixtures
    $left = Sql "SELECT (SELECT COUNT(*) FROM t_ctms_contract WHERE name LIKE 'CTMSAL%' OR contract_no LIKE 'CTMSAL%') + (SELECT COUNT(*) FROM t_ctms_customer WHERE id LIKE 'CCOMCUST%') + (SELECT COUNT(*) FROM t_ctms_product WHERE id LIKE 'CCOMPROD%') + (SELECT COUNT(*) FROM t_ctms_product_type WHERE id LIKE 'CCOMTYPE%') + (SELECT COUNT(*) FROM t_ctms_uom WHERE id LIKE 'CCOMUOM%')"
    Info "夹具残留行数（应为 0）：$left"
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
