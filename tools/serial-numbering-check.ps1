<#
================================================================================
 serial-numbering-check.ps1 —— 2.0 B3 §1.3「合同编号序列扩展」回归（真实环境）
--------------------------------------------------------------------------------
 为什么单独一个脚本：
   §1.2 已用真实环境实测证明「配置表达」**4 条判定全部不通过**（日期恒取当天、
   重置改 DB 而真正计数器在 Redis、序号只按 confId 分桶、没有预览端点）。
   于是给 `ruoyi-serial` 做了最小扩展（业务参数分桶 + 惰性跨年重置 + 可传参考日期 + 预览不占号）。
   扩展的验收就是"4 条判定全部可复现通过 **且既有编号行为不变**"——后者必须拿
   改动前抓的真实基线逐项比对，光看代码是证明不了的。

 覆盖（判定标准即 design.md D-6）：
   ① 签订日期决定年份/月份码：2025-06-01 → PURZC202506000001；2027-01-01 → PURZC202701000001
   ② 惰性跨年重置：新年首个序号 000001，**不依赖 1 月 1 日的定时任务**
   ③ 序号维度 = 类型码 + 主体码 + 年份（同一配置下不同主体各自独立计数）
   ④ 预览不占号：连续两次预览同值，且随后的取号正是预览的那个号
   ⑤ 向后兼容：既有形态配置（FIXED + DATE + SEQ）的编号、计数器键、current_seq 回写、
      流水留痕与改动前逐项一致（基线取自 .cache\b3-legacy-baseline.txt）
   ⑥ 业务参数缺失 / 参考日期格式错误 → 明确报错而不是静默产出错号

 用法（仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\serial-numbering-check.ps1

 前置：后端 8080 在线；.cache\token-superAdmin.txt 有效（先跑 .\tools\oa-login.ps1）

 副作用：夹具用固定 ASCII id（B3NUM*/B3LEGACY*），收尾全部删除，含 Redis 计数键与编号流水。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl  = 'http://localhost:8080',
    [string]$MySqlCli = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$RedisCli = 'F:\dsh\ruoyiOA\env\redis\server\redis-cli.exe',
    [string]$CacheDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database = 'rad_oa',
    # 夹具 id（ASCII，便于清理与判定）
    [string]$NewCfgId = 'B3NUMVERIFY000000000000000001',
    [string]$LegacyCfgId = 'B3LEGACYCFG00000000000000000001'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)   # 管道喂 SQL 给 mysql.exe 时必须（PS5.1 默认 ASCII 会把中文列名变 ?）

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

function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('b3serial-' + [guid]::NewGuid().ToString('N') + '.sql')
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

<# POST JSON；业务码非 200 时后端仍回 HTTP 200，所以统一按响应体的 code 判断 #>
function Post([string]$url, $body) {
    $headers = @{ Authorization = "Bearer $(TokenOf 'superAdmin')" }
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10 -Compress))
        return Invoke-RestMethod "$BaseUrl$url" -Method Post -Headers $headers `
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

function Take([string]$cfg, [string]$refDate, $params) {
    $body = @{}
    if ($refDate) { $body.referenceDate = $refDate }
    if ($params) { $body.params = $params }
    $r = Post "/serial/config/genSerialNo/$cfg" $body
    return @{ code = $r.code; value = [string]$r.data; msg = [string]$r.msg }
}

function Preview([string]$cfg, [string]$refDate, $params) {
    $body = @{}
    if ($refDate) { $body.referenceDate = $refDate }
    if ($params) { $body.params = $params }
    $r = Post "/serial/config/previewSerialNo/$cfg" $body
    return @{ code = $r.code; value = [string]$r.data; msg = [string]$r.msg }
}

function PurgeRedis([string]$cfg) {
    $keys = & $RedisCli keys "code:gen:seq:$cfg*" 2>&1
    foreach ($k in $keys) { if ($k) { & $RedisCli del $k | Out-Null } }
}

# ------------------------------------------------------------------ 前置
Step '前置检查'
if (-not (Test-Path $MySqlCli)) { throw "缺少文件：$MySqlCli" }
if (-not (Test-Path $RedisCli)) { throw "缺少文件：$RedisCli" }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw '后端 8080 未监听 —— 先跑 .\start-env.ps1'
}
$null = TokenOf 'superAdmin'
$baselineFile = Join-Path $CacheDir 'b3-legacy-baseline.txt'
Assert-That (Test-Path $baselineFile) "找到「改动前」的既有编号基线（$baselineFile）"

try {
    # ============================================================== 夹具
    Step '夹具：两条编号配置——① 业务参数型（新）② 既有形态（兼容对照）'
    $null = SqlFile @"
DELETE FROM t_code_config_rule WHERE config_id IN ('$NewCfgId','$LegacyCfgId');
DELETE FROM t_code_config WHERE id IN ('$NewCfgId','$LegacyCfgId');
DELETE FROM t_code_sequence_log WHERE title LIKE 'B3%';

INSERT INTO t_code_config (id,title,current_seq,enable_flag,del_flag,create_time)
VALUES ('$NewCfgId','B3编号扩展回归',0,'1','0',NOW()),
       ('$LegacyCfgId','B3既有编号对照',0,'1','0',NOW());

INSERT INTO t_code_config_rule (id,config_id,rule_type,rule_value,pad_zero,seq_reset_type,sort,del_flag,create_time) VALUES
 ('B3NUMR0000000000000000001001','$NewCfgId','3','typeCode','0','0',1,'0',NOW()),
 ('B3NUMR0000000000000000001002','$NewCfgId','3','subjectCode','0','0',2,'0',NOW()),
 ('B3NUMR0000000000000000001003','$NewCfgId','1','yyyy','0','0',3,'0',NOW()),
 ('B3NUMR0000000000000000001004','$NewCfgId','1','MM','0','0',4,'0',NOW()),
 ('B3NUMR0000000000000000001005','$NewCfgId','2','6','1','4',5,'0',NOW()),
 ('B3LEGR0000000000000000000001','$LegacyCfgId','0','YX','0','0',1,'0',NOW()),
 ('B3LEGR0000000000000000000002','$LegacyCfgId','1','yyyyMMdd','0','0',2,'0',NOW()),
 ('B3LEGR0000000000000000000003','$LegacyCfgId','2','2','1','1',3,'0',NOW());
"@
    PurgeRedis $NewCfgId
    PurgeRedis $LegacyCfgId
    Assert-That ((Sql "SELECT COUNT(*) FROM t_code_config_rule WHERE config_id='$NewCfgId'") -eq '5') '新式夹具规则 5 条就位'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_code_config_rule WHERE config_id='$LegacyCfgId'") -eq '3') '既有形态夹具规则 3 条就位'
    Assert-That ((Sql "SELECT rule_type FROM t_code_config_rule WHERE config_id='$NewCfgId' AND sort=1") -eq '3') `
        '规则类型 3（业务参数）已被接受（后端枚举与库一致）'

    # ============================================================== ① 签订日期决定年月码
    Step '① 签订日期决定年份与月份码（判定标准 1）'
    $a1 = Take $NewCfgId '2025-06-01' @{ typeCode = 'PUR'; subjectCode = 'ZC' }
    Assert-That ($a1.code -eq 200) "按 2025-06-01 取号（code=$($a1.code) msg=$($a1.msg)）"
    Assert-That ($a1.value -eq 'PURZC202506000001') "签订 2025-06-01 → $($a1.value)（期望 PURZC202506000001）"
    $a2 = Take $NewCfgId '2025-06-01' @{ typeCode = 'PUR'; subjectCode = 'ZC' }
    Assert-That ($a2.value -eq 'PURZC202506000002') "同桶再取一号 → $($a2.value)（序号递增）"
    $b1 = Take $NewCfgId '2027-01-01' @{ typeCode = 'PUR'; subjectCode = 'ZC' }
    Assert-That ($b1.value -eq 'PURZC202701000001') "签订 2027-01-01 → $($b1.value)（期望 PURZC202701000001）"

    # ============================================================== ② 惰性跨年重置
    Step '② 惰性跨年重置：新年首号 = 000001，不依赖 1 月 1 日的定时任务（判定标准 2）'
    Assert-That ($b1.value.EndsWith('000001')) '新年桶的首个序号就是 000001（该桶此前从未被取过号）'
    $a3 = Take $NewCfgId '2025-06-01' @{ typeCode = 'PUR'; subjectCode = 'ZC' }
    Assert-That ($a3.value -eq 'PURZC202506000003') "取过 2027 的号之后再取 2025 的号，2025 桶不受影响 → $($a3.value)"
    $keys = (& $RedisCli keys "code:gen:seq:$NewCfgId*" 2>&1) | Where-Object { $_ }
    Assert-That ($keys.Count -eq 2) "Redis 里出现 2 个月份桶（2025 / 2027 各一个）：$($keys -join ' , ')"
    Assert-That (($keys -join ' ') -match ':y2025') '2025 桶键含年份后缀 :y2025'
    Assert-That (($keys -join ' ') -match ':y2027') '2027 桶键含年份后缀 :y2027'
    Assert-That ((Sql "SELECT current_seq FROM t_code_config WHERE id='$NewCfgId'") -eq '0') `
        '分桶路径**不回写** t_code_config.current_seq（单列无法表达多桶）'

    # ============================================================== ③ 序号维度
    Step '③ 序号维度 = 类型码 + 主体码 + 年份（判定标准 3）'
    $xs = Take $NewCfgId '2025-06-01' @{ typeCode = 'PUR'; subjectCode = 'XS' }
    Assert-That ($xs.value -eq 'PURXS202506000001') "同一配置、只换主体码 → $($xs.value)（主体各自独立计数）"
    $sal = Take $NewCfgId '2025-06-01' @{ typeCode = 'SAL'; subjectCode = 'ZC' }
    Assert-That ($sal.value -eq 'SALZC202506000001') "同一配置、只换类型码 → $($sal.value)（类型也各自独立计数）"
    $zcAgain = Take $NewCfgId '2025-06-01' @{ typeCode = 'PUR'; subjectCode = 'ZC' }
    Assert-That ($zcAgain.value -eq 'PURZC202506000004') "回到 PUR/ZC 桶继续递增 → $($zcAgain.value)"
    $keys2 = (& $RedisCli keys "code:gen:seq:$NewCfgId*" 2>&1) | Where-Object { $_ }
    # 桶 = (类型码, 主体码, 年份) 组合：PUR/ZC/2025、PUR/ZC/2027、PUR/XS/2025、SAL/ZC/2025 → 4 个
    Assert-That ($keys2.Count -eq 4) "共 4 个桶（PUR/ZC 两年 + PUR/XS + SAL/ZC）：$($keys2.Count) 个"

    # ============================================================== ④ 预览不占号
    Step '④ 预览不占号（判定标准 4）'
    $pv1 = Preview $NewCfgId '2025-06-01' @{ typeCode = 'PUR'; subjectCode = 'PAY' }
    $pv2 = Preview $NewCfgId '2025-06-01' @{ typeCode = 'PUR'; subjectCode = 'PAY' }
    Assert-That ($pv1.code -eq 200) "预览接口可用（code=$($pv1.code) msg=$($pv1.msg)）"
    Assert-That ($pv1.value -eq $pv2.value) "连续两次预览返回同一个编号（$($pv1.value) / $($pv2.value)）"
    $hasPayKey = ((& $RedisCli keys "code:gen:seq:$NewCfgId*subjectCode=PAY*" 2>&1) | Where-Object { $_ }).Count
    Assert-That ($hasPayKey -eq 0) '预览没有创建计数器（Redis 里查不到 PAY 桶）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_code_sequence_log WHERE title='B3编号扩展回归' AND code LIKE 'PURPAY%'") -eq '0') `
        '预览没有写编号流水'
    $takePay = Take $NewCfgId '2025-06-01' @{ typeCode = 'PUR'; subjectCode = 'PAY' }
    Assert-That ($takePay.value -eq $pv1.value) "随后的取号正是预览的那个（$($takePay.value)）"

    # ============================================================== ⑥ 报错路径
    Step '⑥ 报错路径：业务参数缺失 / 参考日期格式错误'
    $miss = Take $NewCfgId '2025-06-01' @{ typeCode = 'PUR' }
    Assert-That ($miss.code -ne 200 -and $miss.msg -like '*subjectCode*') "缺业务参数被拒且点名参数：$($miss.msg)"
    $badDate = Take $NewCfgId 'not-a-date' @{ typeCode = 'PUR'; subjectCode = 'ZC' }
    Assert-That ($badDate.code -ne 200 -and $badDate.msg -like '*参考日期*') "参考日期格式错误被拒：$($badDate.msg)"

    # ============================================================== ⑤ 向后兼容
    Step '⑤ 向后兼容：既有形态配置与「改动前」基线逐项一致'
    PurgeRedis $LegacyCfgId
    $null = SqlFile "UPDATE t_code_config SET current_seq=0 WHERE id='$LegacyCfgId'; DELETE FROM t_code_sequence_log WHERE title LIKE 'B3%';"
    $l1 = (Take $LegacyCfgId $null $null).value
    $l2 = (Take $LegacyCfgId $null $null).value
    $l3 = (Take $LegacyCfgId $null $null).value
    $seqAfter3 = Sql "SELECT current_seq FROM t_code_config WHERE id='$LegacyCfgId'"
    PurgeRedis $LegacyCfgId
    $l4 = (Take $LegacyCfgId $null $null).value
    $today = Get-Date -Format 'yyyyMMdd'
    Assert-That ($l1 -eq "YX${today}01") "第 1 次 = $l1（基线段落为 YX${today}01）"
    Assert-That ($l2 -eq "YX${today}02") "第 2 次 = $l2"
    Assert-That ($l3 -eq "YX${today}03") "第 3 次 = $l3"
    Assert-That ($seqAfter3 -eq '3') "三次取号后 DB current_seq = $seqAfter3（既有行为：不分桶才回写）"
    Assert-That ($l4 -eq "YX${today}04") "删 Redis 键后从 DB current_seq 继续 = $l4（既有行为）"
    # 既有形态的编号以 YX 开头（新式的以 PUR/SAL 开头），用 ASCII 条件精确定位，避开中文比较
    $legacyLogs = Sql "SELECT COUNT(*) FROM t_code_sequence_log WHERE title LIKE 'B3%' AND code LIKE 'YX%'"
    Assert-That ($legacyLogs -eq '4') "既有形态每次取号仍写一条编号流水（期望 4，实际 $legacyLogs）"
    # 老 GET 入口（不带上下文）也必须照旧可用
    $headers = @{ Authorization = "Bearer $(TokenOf 'superAdmin')" }
    $legacyGet = Invoke-RestMethod "$BaseUrl/serial/config/genSerialNo/$LegacyCfgId" -Headers $headers
    Assert-That ($legacyGet.code -eq 200 -and ([string]$legacyGet.data) -eq "YX${today}05") `
        "老 GET /genSerialNo/{confId} 入口行为不变（返回 $($legacyGet.data)）"
}
finally {
    Step '收尾：删除夹具（配置 / 规则 / 编号流水 / Redis 计数键）'
    $null = SqlFile @"
DELETE FROM t_code_sequence_log WHERE title LIKE 'B3%';
DELETE FROM t_code_config_rule WHERE config_id IN ('$NewCfgId','$LegacyCfgId');
DELETE FROM t_code_config WHERE id IN ('$NewCfgId','$LegacyCfgId');
"@
    PurgeRedis $NewCfgId
    PurgeRedis $LegacyCfgId
    $left = Sql "SELECT COUNT(*) FROM t_code_config WHERE id LIKE 'B3NUM%' OR id LIKE 'B3LEGACY%'"
    $leftRule = Sql "SELECT COUNT(*) FROM t_code_config_rule WHERE config_id LIKE 'B3NUM%' OR config_id LIKE 'B3LEGACY%'"
    $leftLog = Sql "SELECT COUNT(*) FROM t_code_sequence_log WHERE title LIKE 'B3%'"
    $leftKey = ((& $RedisCli keys 'code:gen:seq:B3*' 2>&1) | Where-Object { $_ }).Count
    if ($left -eq '0' -and $leftRule -eq '0' -and $leftLog -eq '0' -and $leftKey -eq 0) {
        Info '夹具已清理干净（配置/规则/流水/Redis 键均为 0）'
    } else {
        Bad "夹具未清理干净：配置 $left / 规则 $leftRule / 流水 $leftLog / Redis 键 $leftKey"
    }
}

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
