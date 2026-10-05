<#
================================================================================
 ctms-migration-check.ps1 —— 历史甲乙方迁移工具的真库语义验收
                              （第 7 组 7.1~7.3 复核资产 / t26 沉淀，供 10.1/10.3 复用）
--------------------------------------------------------------------------------
 为什么必须"真库 + 接口"：
   迁移的两个关键口径都活在**数据库里**，内存桩单测证明不了：
     ① 扫描的取数走 `includeDeleted="1"`（已停用合同必须进扫描）—— 桩里 includeDeleted
        只是我自己写的分支；
     ② 扫描分组键与认领候选比较都依赖 **文本比较口径**（utf8mb4_0900_ai_ci 的空白/大小写语义）。
        桩若沿用 Java Collator 的松口径，"候选比较漏了 trim" 这类缺陷在服务层用例里**根本不会变红**
        —— t26 实测踩过：把桩的比较去掉 trim，用例照样全绿（Collator 把空白当可忽略元素，
        而 MySQL 的 ai_ci 空白显著）。所以这条链路的证据只能在真库上取。
   本脚本还顺手钉住"扫描只读"（迁移工具最危险的副作用）：跑完扫描后逐合同指纹必须一字不变。

 覆盖（每条都对应一个只有真实 SQL 才能证明的语义）：
   S1  includeDeleted="1" 生效：**已停用**的采购合同（del_flag='2'）也进扫描并生成草案
   S1b 扫描只读：扫描前后合同表行数 + 关键列指纹完全一致
   S2  ai_ci 文本匹配：'CTMMIG Acme Valves' 与 'ctmmig acme valves' 聚合成**一条**草案，认领后两条都被绑定
   S3  is null 候选过滤：已绑定**别的**档案的同文本合同不被覆盖
   S4  认领写档案引用 + 每份合同一条含操作人的「供应商档案」变更历史（source=auto）
   S5  供应商简称兜底：新建档案时不传 shortName → short_name = 草案 raw_name
   S6  F1-空格：同一可见文本的四种空格写法聚合成一条草案，认领后**四份都真被绑定**，再扫描**稳定停在 claimed**
   S7  F1-Tab：`\t文本\t` 的 raw_name 原样保留（两侧都不去），认领能真正绑定
   S8  F1-全角空格：U+3000 两侧都保留，认领能真正绑定
   S9  claimed 回归：认领后再出现同文本未绑定合同 → 草案回到 pending 且清 matched_id
   S10 ignored 不复活：忽略后再扫描仍是 ignored
   S11 已认领拒绝：重复认领的提示必须含关键字「已认领」
   S12 口径自诊断：任何一次认领若出现「草案计数 > 0 而本次实绑 0」，直接判失败并打印诊断线索
       （这正是 t26 / t16 F1 的症状形态；修复后它必须永不出现）
   S13 静态口径自证：认领候选 SQL 两侧都写 trim( )，且 Java 侧的 trimSpaces 存在
       （改任一侧都会让 S6~S8 在真库上变红，这里是"文档之外的第二道锁"）

 用法（仓库根目录；先 start-env.ps1 + oa-login.ps1）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-migration-check.ps1
   -Database rad_oa -Force        # 跳过并发锁
   -BaseUrl http://localhost:8080

 前置：① 8080 在线，且跑的是**含迁移端点的 jar**（脚本第一步会自证：jar 时间戳 + 四端点非 404/401，
          不成立直接退出码 2，避免拿旧 jar 出假证据）；
       ② `.cache\token-superAdmin.txt` 有效（oa-login.ps1 产出）；
       ③ rad_oa 已执行 二开-合同台账.sql 与字典/菜单 SQL。

 副作用：夹具合同统一用本轮 run tag 生成编号（`PURZC202610<run><i>` / `SALZC202610<run><i>`），
         收尾按**显式 id 清单**物理删除（合同 / 行项 / 变更历史 / 认领新建的档案 / 草案），幂等；
         不碰任何非夹具行；不改系统时间；不 DROP 库。
 经验（沿用 ctms-contract-check.ps1 的三条）：
   1. 不可并发执行：内置锁 `.cache/ctms-migration-check.lock`（-Force 跳过）。
   2. 含中文/制表符的 SQL 一律 stdin 喂 mysql.exe，并在脚本开头设 $OutputEncoding（DEV-ENV §6.33）；
      命令行参数位置不要用 `+` 拼串（§6.44）。
   3. 断言必须按 id + 带前后对照，"结果里有它"不够（同状态下还有别的行时没有区分力）。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl  = 'http://localhost:8080',
    [string]$MySqlCli = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database = 'rad_oa',
    [string]$User     = 'superAdmin',
    [string]$JarPath  = 'F:\dsh\ruoyiOA\ruoyi-vue-oa-master\ruoyi-admin\target\ruoyi-admin.jar',
    [string]$XmlPath  = 'F:\dsh\ruoyiOA\ruoyi-vue-oa-master\ruoyi-ctms\src\main\resources\mapper\ctms\CtmsContractMapper.xml',
    [string]$RulesPath = 'F:\dsh\ruoyiOA\ruoyi-vue-oa-master\ruoyi-ctms\src\main\java\com\ruoyi\ctms\support\MigrationRules.java',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$script:Pass = 0
$script:Fail = 0
$script:Failures = @()
$script:ContractNos = New-Object System.Collections.ArrayList
$script:ContractIds = New-Object System.Collections.ArrayList
$script:DraftIds = New-Object System.Collections.ArrayList
$script:PartyIds = New-Object System.Collections.ArrayList

function Ok([string]$m)  { Write-Host ("  [OK]   " + $m) -ForegroundColor Green }
function Bad([string]$m) { Write-Host ("  [FAIL] " + $m) -ForegroundColor Red }
function Info([string]$m) { Write-Host ("  [--]   " + $m) -ForegroundColor DarkGray }

function Assert-That([bool]$cond, [string]$desc) {
    if ($cond) { Ok $desc; $script:Pass++ } else { Bad $desc; $script:Fail++; $script:Failures += $desc }
}

function Stop-With([string]$msg, [int]$code) {
    Write-Host ""
    Write-Host ("前置不成立，已停止：" + $msg) -ForegroundColor Red
    Write-Host "（本次未产出任何验收证据；退出码 $code）" -ForegroundColor Red
    exit $code
}

function TokenOf([string]$user) {
    $f = Join-Path $CacheDir "token-$user.txt"
    if (-not (Test-Path $f)) { throw "缺少 token 文件：$f（先跑 .\tools\oa-login.ps1）" }
    return (Get-Content $f -Raw).Trim()
}

function Api([string]$method, [string]$path, $bodyObj) {
    $headers = @{ Authorization = "Bearer $(TokenOf $User)" }
    $url = $BaseUrl + $path
    try {
        if ($null -ne $bodyObj) {
            $json = $bodyObj | ConvertTo-Json -Compress -Depth 6
            return Invoke-RestMethod -Method $method -Uri $url -Headers $headers `
                -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
        }
        return Invoke-RestMethod -Method $method -Uri $url -Headers $headers
    } catch {
        # 接口异常（HTTP 非 2xx）不让整轮中断：记一条失败并返回可判的空壳。
        # 否则一个坏调用会把后面的断言全部吞掉（t26 第一次跑就因为 S7 的空 id 丢掉了 S8~S12 的证据）。
        $c = 0
        try { $c = [int]$_.Exception.Response.StatusCode.value__ } catch { $c = -1 }
        $b = ''
        try {
            $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $b = $sr.ReadToEnd()
        } catch { $b = $_.Exception.Message }
        $msg = [string]$b
        if ($msg.Length -gt 160) { $msg = $msg.Substring(0, 160) + '...' }
        Bad ("接口异常：$method $path → HTTP $c $msg")
        $script:Fail++
        $script:Failures += "接口异常 $method $path HTTP $c"
        return [pscustomobject]@{ code = $c; msg = $msg; data = $null; rows = @() }
    }
}

function Show-Row([string]$what, $r) {
    if ($null -eq $r) { Info ($what + " → 无返回"); return }
    $cc = '-'; $mid = '-'
    if ($null -ne $r.data) {
        try { $cc = $r.data.contractCount } catch { }
        try { $mid = $r.data.matchedId } catch { }
        if ($null -eq $cc) { $cc = '-' }
        if ($null -eq $mid) { $mid = '-' }
    }
    Info ("$what → code=$($r.code) msg=$($r.msg) 实绑=$cc 档案=$mid")
}

function Claim-Draft($draft, $bodyObj, [string]$what) {
    if ($null -eq $draft) {
        Assert-That $false ("$what：草案不存在，认领被跳过（上一步已失败，先修上一步）")
        return $null
    }
    $r = Api 'POST' ("/ctms/migration/drafts/" + $draft.id + "/claim") $bodyObj
    Show-Row $what $r
    if ($null -ne $r -and $null -ne $r.data -and -not [string]::IsNullOrEmpty($r.data.matchedId)) {
        $null = $script:PartyIds.Add($r.data.matchedId)
    }
    return $r
}

function ApiProbe([string]$method, [string]$path, $bodyObj) {
    # 只取 HTTP 状态 + 响应体（用于前置自证：区分 404 / 401 / 业务错误）
    $headers = @{ Authorization = "Bearer $(TokenOf $User)" }
    $url = $BaseUrl + $path
    try {
        if ($null -ne $bodyObj) {
            $json = $bodyObj | ConvertTo-Json -Compress -Depth 6
            $r = Invoke-WebRequest -Method $method -Uri $url -Headers $headers `
                -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -UseBasicParsing
        } else {
            $r = Invoke-WebRequest -Method $method -Uri $url -Headers $headers -UseBasicParsing
        }
        $txt = [string]$r.Content
        if ($txt.Length -gt 160) { $txt = $txt.Substring(0, 160) + '...' }
        return @{ code = [int]$r.StatusCode; body = $txt }
    } catch {
        $c = 0
        try { $c = [int]$_.Exception.Response.StatusCode.value__ } catch { $c = -1 }
        $b = ''
        try {
            $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $b = $sr.ReadToEnd()
        } catch { $b = $_.Exception.Message }
        if ($b.Length -gt 160) { $b = $b.Substring(0, 160) + '...' }
        return @{ code = $c; body = $b }
    }
}

function Sql([string]$query) {
    # 含中文/制表符的 SQL 一律 stdin 喂 mysql.exe（DEV-ENV §6.33）
    return @($query | & $MySqlCli --host=127.0.0.1 --user=root "--database=$Database" `
        --batch --skip-column-names --default-character-set=utf8mb4 2>&1 |
        Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}

function SqlScalar([string]$query) {
    # ⚠ 必须用 @() 重新包一层：PowerShell 会把「单元素数组」解包成标量字符串，
    #    此时 $rows[0] 取到的是**第一个字符**（32 位 id 会变成 '0'）—— t26 第一次跑就栽在这里，
    #    现象是"每个 fixture 的 id 都是 0、认领永远报草案不存在"。
    $rows = @(Sql $query)
    if ($rows.Count -eq 0) { return '' }
    return ([string]$rows[0]).Trim()
}

function SqlExec([string]$sqlText) {
    $out = @(Sql $sqlText)
    $err = @($out | Where-Object { $_ -match 'ERROR \d{4}' })
    if ($err.Count -gt 0) { throw ("SQL 执行失败：" + ($err -join ' | ')) }
    return $out
}

function New-Fixture([string]$prefix, [string]$type, [string]$partyA, [string]$partyB, [string]$noSuffix) {
    # 编号规则真源：ContractNumberRules.NO_PATTERN = ^[A-Z]{3}[A-Z]{2}\d{4}\d{2}\d{6}$（共 17 位）
    # 所以：11 位前缀（PURZC202610）+ 4 位 mmss + 2 位序号 = 17 位，且逐次运行都不同。
    $no = $prefix + $script:SeqBase + $noSuffix
    $null = Api 'POST' '/ctms/contract' @{
        contractNo = $no; name = ("迁移验收夹具 " + $noSuffix); type = $type
        partyA = $partyA; partyB = $partyB
    }
    $id = SqlScalar "select id from t_ctms_contract where contract_no = '$no'"
    if ([string]::IsNullOrWhiteSpace($id)) { throw "夹具合同未落库：$no" }
    $null = $script:ContractNos.Add($no)
    $null = $script:ContractIds.Add($id)
    return @{ id = $id; no = $no }
}

function Set-PartyText([string]$id, [string]$partyA, [string]$partyB) {
    # 直接用 SQL 注入"存量导入脏数据"形态的原文（带首尾空格/制表符/全角空格）。
    # 刻意不走接口：真实迁移面对的就是**历史导入**留下的原文，接口侧的归一不参与这个场景；
    # 这样 F1 的每一类空白都是确定性的，不受服务层 pick/normalize 影响。
    $a = if ($null -eq $partyA) { '' } else { $partyA }
    $b = if ($null -eq $partyB) { '' } else { $partyB }
    $null = SqlExec "UPDATE t_ctms_contract SET party_a = '$a', party_b = '$b' WHERE id = '$id';"
}

function New-OtherSupplier([string]$code, [string]$name, [string]$shortName) {
    # 走接口建"别的档案"（服务层会补 id/审计列，且过 PartnerRules 必填校验）
    $null = Api 'POST' '/ctms/partner/supplier' @{ code = $code; name = $name; shortName = $shortName }
    $id = SqlScalar "select id from t_ctms_supplier where code = '$code'"
    if ([string]::IsNullOrWhiteSpace($id)) { throw "既有供应商档案未落库：$code" }
    $null = $script:PartyIds.Add($id)
    return $id
}

function DraftOf([string]$partyType, [string]$rawName) {
    $rows = @(Sql "select id, status, ifnull(matched_id,''), contract_count from t_ctms_party_draft where party_type = '$partyType' and raw_name = '$rawName'")
    if ($rows.Count -eq 0) { return $null }
    $f = ([string]$rows[0]).Split("`t")
    $null = $script:DraftIds.Add($f[0])
    return @{ id = $f[0]; status = $f[1]; matchedId = $f[2]; count = [int]$f[3] }
}

function Fingerprint() {
    # 合同表指纹：行数 + 关键列拼接的哈希（扫描只读的判据）
    $raw = (@(Sql "select count(*), ifnull(sum(crc32(concat_ws('|', id, ifnull(party_a,''), ifnull(party_b,''), ifnull(customer_id,'x'), ifnull(supplier_id,'x'), del_flag))),0) from t_ctms_contract")) -join ''
    return $raw.Trim()
}

function Clear-Fixtures() {
    if ($script:ContractIds.Count -eq 0 -and $script:DraftIds.Count -eq 0 -and $script:PartyIds.Count -eq 0) { return }
    $cids = ($script:ContractIds | Select-Object -Unique) -join "','"
    $dids = ($script:DraftIds | Select-Object -Unique) -join "','"
    $pids = ($script:PartyIds | Select-Object -Unique) -join "','"
    $sql = @()
    if ($cids) {
        $sql += "DELETE FROM t_ctms_change_log WHERE object_id IN ('$cids');"
        $sql += "DELETE FROM t_ctms_contract_item WHERE contract_id IN ('$cids');"
        $sql += "DELETE FROM t_ctms_contract_tag WHERE contract_id IN ('$cids');"
        $sql += "DELETE FROM t_ctms_contract WHERE id IN ('$cids');"
    }
    if ($dids) { $sql += "DELETE FROM t_ctms_party_draft WHERE id IN ('$dids');" }
    if ($pids) {
        $sql += "DELETE FROM t_ctms_supplier WHERE id IN ('$pids');"
        $sql += "DELETE FROM t_ctms_customer WHERE id IN ('$pids');"
    }
    $null = SqlExec ($sql -join ' ')
}

$script:LockFile = Join-Path $CacheDir 'ctms-migration-check.lock'
$script:RunTag = (Get-Date -Format 'HHmmss')
$script:SeqBase = $script:RunTag.Substring(2)   # mmss（4 位），配 2 位序号组成编号的最后 6 位

Write-Host ""
Write-Host "=== ctms-migration-check：历史甲乙方迁移工具真库语义验收 ===" -ForegroundColor Cyan
Write-Host ("    库=$Database  后端=$BaseUrl  账号=$User  runTag=$($script:RunTag)")
Write-Host ""

# ==================== §0 前置：运行态自证（不成立就停止，绝不用旧 jar 出证据） ====================
Write-Host "[§0] 前置自证：端点是否真的跑在新 jar 上" -ForegroundColor Yellow

if (-not (Test-Path $JarPath)) { Stop-With "找不到 $JarPath" 2 }
$jar = Get-Item $JarPath
Info ("ruoyi-admin.jar 时间戳 = " + $jar.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + "  大小 = " + $jar.Length)

$legalId = '0123456789abcdef0123456789abcdef'
$probeScan = ApiProbe 'POST' '/ctms/migration/scan' $null
$probeList = ApiProbe 'GET'  '/ctms/migration/drafts?pageNum=1&pageSize=1' $null
$probeClaim = ApiProbe 'POST' ("/ctms/migration/drafts/$legalId/claim") @{ code = 'CTMMIG-PROBE' }
$probeIgnore = ApiProbe 'POST' ("/ctms/migration/drafts/$legalId/ignore") $null
$probeNope = ApiProbe 'GET' '/ctms/migration/nope-not-here' $null

Info ("scan=$($probeScan.code) drafts=$($probeList.code) claim=$($probeClaim.code) ignore=$($probeIgnore.code) 对照=$($probeNope.code)")
if ($probeScan.code -eq 404 -or $probeList.code -eq 404 -or $probeClaim.code -eq 404 -or $probeIgnore.code -eq 404) {
    Stop-With "迁移端点在 8080 上返回 404 —— 运行中的是**旧 jar**，请按 DEV-ENV §6.13 重打包重启后重跑" 2
}
if ($probeScan.code -eq 401 -or $probeList.code -eq 401 -or $probeClaim.code -eq 401 -or $probeIgnore.code -eq 401) {
    Stop-With "端点返回 401 —— token 失效，请先跑 tools/oa-login.ps1 重新登录" 2
}
Assert-That ($probeScan.code -eq 200 -and $probeList.code -eq 200) "迁移端点在运行态可用（scan/drafts = 200）"
# ⚠ 合法形态 id 的 claim/ignore 拿到的是业务错误（草案不存在），**不能**用 __NOPE__ 这类下划线 id 判端点缺失：
#    {id:[A-Za-z0-9]+} 的正则本身就会把非法 id 拒成 404，与"端点不存在"同形（t25 实测坑）。
Assert-That ($probeClaim.code -eq 200 -and $probeClaim.body -match '草案不存在') `
    "claim 端点存在（合法 32 位 id → 200 + 业务错误「草案不存在」，不是 404）"
Assert-That ($probeIgnore.code -eq 200 -and $probeIgnore.body -match '草案不存在') `
    "ignore 端点存在（同款判据）"
Assert-That ($probeNope.code -eq 404) "对照组：不存在的路径确实返回 404（说明上面的 200 不是"什么都返回 200"）"

# ==================== §1 静态口径自证（改一侧就红） ====================
Write-Host ""
Write-Host "[§1] 静态口径自证：候选比较两侧都 trim" -ForegroundColor Yellow
$xml = Get-Content -Raw -Encoding UTF8 $XmlPath
$trimHits = ([regex]::Matches($xml, 'trim\(')).Count
$rules = Get-Content -Raw -Encoding UTF8 $RulesPath
Assert-That ($trimHits -ge 4) ("CtmsContractMapper.xml 里 trim( 命中 $trimHits 次（候选 SQL 的客户/供应商两方向各两侧 trim）")
Assert-That ($xml -match 'trim\(c\.party_a\)\s*=\s*trim\(#\{rawName\}\)') "客户方向：trim(c.party_a) = trim(#{rawName})"
Assert-That ($xml -match 'trim\(c\.party_b\)\s*=\s*trim\(#\{rawName\}\)') "供应商方向：trim(c.party_b) = trim(#{rawName})"
Assert-That ($rules -match 'public static String trimSpaces\(String value\)') "Java 侧存在同一个口径的实现 MigrationRules.trimSpaces"
Assert-That ($rules -match '只去首尾 ASCII 空格') "Java 侧注释写明"只去首尾 ASCII 空格"（改口径必须同步改注释与两侧）"

# ==================== 夹具与探测 ====================
$stamp = $script:RunTag
$T_SUP   = "CTMMIG 阀门 $stamp"          # 供应商方向基准文本
$T_STOP  = "CTMMIG 停用阀门 $stamp"      # S1：只出现在已停用合同上
$T_CASE  = "CTMMIG Acme Valves $stamp"   # S2：大小写变体
$T_BOUND = "CTMMIG 已绑定 $stamp"        # S3：已绑定别的档案
$T_WS    = "CTMMIG 空格阀门 $stamp"      # S6：空格四变体
$T_TAB   = "CTMMIG 制表阀门 $stamp"      # S7
$T_FW    = "CTMMIG 全角阀门 $stamp"      # S8
$T_IGN   = "CTMMIG 忽略阀门 $stamp"      # S10

try {
    if ((Test-Path $script:LockFile) -and (-not $Force)) {
        $holder = (Get-Content $script:LockFile -Raw -ErrorAction SilentlyContinue)
        throw "检测到运行锁 $($script:LockFile)（$holder）。本脚本不可并发执行；确认无其它实例后删除该文件或用 -Force 跳过。"
    }
    [System.IO.File]::WriteAllText($script:LockFile, "pid=$PID start=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')", (New-Object System.Text.UTF8Encoding($false)))

    # ---- S1：includeDeleted="1"（已停用合同也进扫描）+ 扫描只读 ----
    Write-Host ""
    Write-Host "[S1] includeDeleted=1：已停用合同进扫描；扫描只读" -ForegroundColor Yellow
    $stop = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_STOP '01'
    $null = Api 'DELETE' ("/ctms/contract/" + $stop.id + "?reason=" + [uri]::EscapeDataString('迁移验收：制造已停用合同')) $null
    $preFp = Fingerprint   # ⚠ 基线必须在「制造停用」之后取：DELETE 本身会改 del_flag，放在前面必然误报"扫描改了数据"
    Assert-That ((SqlScalar ("select del_flag from t_ctms_contract where id = '" + $stop.id + "'")) -eq '1') `
        "夹具已停用（del_flag='1'；本仓库口径见 二开-合同台账.sql:322 —— 0 未删除 / 1 已停用）"
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dStop = DraftOf 'supplier' $T_STOP
    Assert-That ($null -ne $dStop) "已停用合同的文本仍被扫描聚合成草案（includeDeleted='1' 生效）"
    Assert-That ((Fingerprint) -eq $preFp) "扫描没有改动合同表（行数与关键列指纹一致）"

    # ---- S1b：不参与方向映射的类型被跳过 + 扫描幂等 ----
    Write-Host ""
    Write-Host "[S1b] OTH 跳过；扫描幂等（数据无变化时无写库动作）" -ForegroundColor Yellow
    # OTH = 其他(历史)：按 MigrationRules.directionOf 不参与映射 → 即使有文本也不得生成草案
    $othText = "CTMMIG 其他类型不该进草案 $stamp"
    $oth = New-Fixture 'OTHZC202610' 'OTH' 'CTMMIG 甲方' $othText '14'
    $null = Api 'POST' '/ctms/migration/scan' $null
    Assert-That ($null -eq (DraftOf 'supplier' $othText) -and $null -eq (DraftOf 'customer' $othText)) `
        "OTH 类型的合同不进扫描（两个方向都没有它的草案）"
    $scanAgain = Api 'POST' '/ctms/migration/scan' $null
    Assert-That ($scanAgain.count -eq 0) "扫描幂等：数据无变化时返回 count=0（没有要写的草案）"
    $dStop2 = DraftOf 'supplier' $T_STOP
    Assert-That ($null -ne $dStop2 -and $dStop2.count -eq 1) "S1 的草案计数仍是 1（重复扫描不改计数）"

    # ---- S2：ai_ci 文本匹配（大小写不同聚合成一条并都被绑定） ----
    Write-Host ""
    Write-Host "[S2] ai_ci 文本匹配：大小写变体聚合成一条草案" -ForegroundColor Yellow
    $c1 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_CASE '02'
    $c2 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_CASE '03'
    # 大小写差异用 SQL 注入，确保是真库里的两种写法（不受接口归一影响）
    Set-PartyText $c1.id 'CTMMIG 我方' $T_CASE
    Set-PartyText $c2.id 'CTMMIG 我方' $T_CASE.ToLower()
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dCase = DraftOf 'supplier' $T_CASE
    Assert-That ($null -ne $dCase) "大小写变体生成了一条草案"
    Assert-That ($null -ne $dCase -and $dCase.count -eq 2) "该草案计数=2（大小写不敏感，两条合同同组）"
    $claimCase = Claim-Draft $dCase @{ code = ("CTMMIGCASE" + $stamp) } "认领(大小写组)"
    Assert-That ($claimCase.code -eq 200) "认领成功（code=200）"
    Assert-That ($claimCase.data.contractCount -eq 2) "实绑计数=2 与真库受影响行数一致（不允许"操作成功但实绑 0"）"
    $pid1 = $claimCase.data.matchedId
    $null = $script:PartyIds.Add($pid1)
    Assert-That ((SqlScalar ("select count(*) from t_ctms_contract where id in ('" + $c1.id + "','" + $c2.id + "') and supplier_id = '" + $pid1 + "'")) -eq '2') `
        "两条合同的 supplier_id 都指向新档案（真库核对）"

    # ---- S3：is null 候选过滤（已绑定别的档案的不被覆盖） ----
    Write-Host ""
    Write-Host "[S3] is null 候选过滤：不覆盖已绑定别的档案的合同" -ForegroundColor Yellow
    $b1 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_BOUND '04'
    $b2 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_BOUND '05'
    # 给 b2 先绑一个"别的"供应商档案（用接口建档案 → 形成"已绑定别的档案"的存量形态）
    $otherId = New-OtherSupplier ("CTMMIG-$stamp") ("CTMMIG 既有供应商 $stamp") 'CTMMIG既有'
    $null = SqlExec ("UPDATE t_ctms_contract SET supplier_id = '$otherId' WHERE id = '" + $b2.id + "';")
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dBound = DraftOf 'supplier' $T_BOUND
    Assert-That ($null -ne $dBound -and $dBound.count -eq 1) "草案只统计未绑定的那 1 条（is null 过滤）"
    $claimBound = Api 'POST' ("/ctms/migration/drafts/" + $dBound.id + "/claim") @{ code = ("CTMMIGBOUND" + $stamp) }
    Show-Row '$claimBound' $claimBound
    $null = $script:PartyIds.Add($claimBound.data.matchedId)
    Assert-That ((SqlScalar ("select supplier_id from t_ctms_contract where id = '" + $b1.id + "'")) -eq $claimBound.data.matchedId) `
        "未绑定的那条被写入新档案"
    Assert-That ((SqlScalar ("select supplier_id from t_ctms_contract where id = '" + $b2.id + "'")) -eq $otherId) `
        "已绑定别的档案的那条**未被覆盖**（仍是 $otherId）"

    # ---- S4/S5：变更历史（含操作人、auto）与简称兜底 ----
    Write-Host ""
    Write-Host "[S4/S5] 认领留痕与简称兜底" -ForegroundColor Yellow
    Assert-That ((SqlScalar ("select count(*) from t_ctms_change_log where object_id in ('" + $c1.id + "','" + $c2.id + "') and field_name = '供应商档案' and source = 'auto' and ifnull(operator_id,'') <> ''")) -eq '2') `
        "每份被绑定的合同各 1 条含操作人的「供应商档案」历史（source=auto）"
    Assert-That ((SqlScalar ("select short_name from t_ctms_supplier where id = '" + $pid1 + "'")) -eq $T_CASE) `
        "未传 shortName 时以草案 raw_name 兜底（short_name = $T_CASE）"
    Assert-That ((SqlScalar ("select party_b from t_ctms_contract where id = '" + $c1.id + "'")) -eq $T_CASE) `
        "认领不改写原文（party_b 原样）"

    # ---- S6：F1-空格（四种写法） ----
    Write-Host ""
    Write-Host "[S6] F1-空格：四种空格写法聚合为一条、四份都真被绑定、再扫描稳定 claimed" -ForegroundColor Yellow
    $w1 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_WS '06'
    $w2 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_WS '07'
    $w3 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_WS '08'
    $w4 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_WS '09'
    # 四种写法：无空格 / 单侧前导 / 单侧尾随 / 双侧多空格（全是"存量导入脏数据"形态）
    Set-PartyText $w1.id 'CTMMIG 我方' $T_WS
    Set-PartyText $w2.id 'CTMMIG 我方' (" " + $T_WS)
    Set-PartyText $w3.id 'CTMMIG 我方' ($T_WS + " ")
    Set-PartyText $w4.id 'CTMMIG 我方' ("   " + $T_WS + "   ")
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dWs = DraftOf 'supplier' $T_WS
    Assert-That ($null -ne $dWs) "带首尾空格的合同仍被扫描聚合成草案（raw_name 已去空格）"
    Assert-That ($null -ne $dWs -and $dWs.count -eq 4) "四种写法聚合成一条，计数=4"
    $claimWs = Api 'POST' ("/ctms/migration/drafts/" + $dWs.id + "/claim") @{ code = ("CTMMIGWS" + $stamp) }
    Show-Row '$claimWs' $claimWs
    $null = $script:PartyIds.Add($claimWs.data.matchedId)
    Assert-That ($claimWs.data.contractCount -eq 4) "F1：实绑计数=4（修复前这里会是 0 或 1 → 永久往复）"
    Assert-That ((SqlScalar ("select count(*) from t_ctms_contract where id in ('" + $w1.id + "','" + $w2.id + "','" + $w3.id + "','" + $w4.id + "') and supplier_id = '" + $claimWs.data.matchedId + "'")) -eq '4') `
        "F1：四份合同的 supplier_id 全部写入（真库核对）"
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dWs2 = DraftOf 'supplier' $T_WS
    Assert-That ($null -ne $dWs2 -and $dWs2.status -eq 'claimed') "再扫描后草案稳定停在 claimed（不回到 pending）"
    Assert-That ($null -ne $dWs2 -and $dWs2.count -eq 4) "再扫描不改写计数（保持 4）"

    # ---- S7：F1-Tab ----
    Write-Host ""
    Write-Host "[S7] F1-Tab：制表符两侧都保留，认领能真正绑定" -ForegroundColor Yellow
    $tabText = "`t" + $T_TAB + "`t"
    $t1 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_TAB '10'
    Set-PartyText $t1.id 'CTMMIG 我方' $tabText
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dTab = DraftOf 'supplier' $tabText
    Assert-That ($null -ne $dTab) "tab 包裹的文本被扫描聚合成草案（raw_name 原样含 tab）"
    if ($null -eq $dTab) {
        Assert-That $false "tab 包裹的文本未生成草案（见上一条），认领跳过"
        $claimTab = $null
    } else {
        $claimTab = Claim-Draft $dTab @{ code = ("CTMMIGTAB" + $stamp) } "认领(Tab 包裹)"
    }
    Assert-That ($null -ne $claimTab -and $claimTab.data.contractCount -eq 1) "tab 类实绑=1（只改 SQL 的 TRIM 时这里会变成 0）"
    Assert-That ($null -ne $claimTab -and (SqlScalar ("select supplier_id from t_ctms_contract where id = '" + $t1.id + "'")) -eq $claimTab.data.matchedId) `
        "tab 包裹的合同真被绑定（真库核对）"
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dTab2 = DraftOf 'supplier' $tabText
    Assert-That ($null -ne $dTab2 -and $dTab2.status -eq 'claimed') "tab 类再扫描后稳定停在 claimed"

    # ---- S8：F1-全角空格 ----
    Write-Host ""
    Write-Host "[S8] F1-全角空格：U+3000 两侧都保留" -ForegroundColor Yellow
    $fwText = [string][char]0x3000 + $T_FW + [string][char]0x3000
    $f1 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_FW '11'
    Set-PartyText $f1.id 'CTMMIG 我方' $fwText
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dFw = DraftOf 'supplier' $fwText
    Assert-That ($null -ne $dFw) "全角空格包裹的文本被扫描聚合成草案（raw_name 原样含 U+3000）"
    if ($null -eq $dFw) {
        Assert-That $false "全角空格包裹的文本未生成草案（见上一条），认领跳过"
        $claimFw = $null
    } else {
        $claimFw = Claim-Draft $dFw @{ code = ("CTMMIGFW" + $stamp) } "认领(全角包裹)"
    }
    Assert-That ($null -ne $claimFw -and $claimFw.data.contractCount -eq 1) "全角类实绑=1"
    Assert-That ($null -ne $claimFw -and (SqlScalar ("select supplier_id from t_ctms_contract where id = '" + $f1.id + "'")) -eq $claimFw.data.matchedId) `
        "全角空格包裹的合同真被绑定（真库核对）"
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dFw2 = DraftOf 'supplier' $fwText
    Assert-That ($null -ne $dFw2 -and $dFw2.status -eq 'claimed') "全角类再扫描后稳定停在 claimed"

    # ---- S9：claimed 回归（认领后又出现同文本未绑定合同） ----
    Write-Host ""
    Write-Host "[S9] claimed 回归：认领后新出现的同文本合同让草案回到 pending" -ForegroundColor Yellow
    $c3 = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_CASE '12'
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dCase2 = DraftOf 'supplier' $T_CASE
    Assert-That ($null -ne $dCase2 -and $dCase2.status -eq 'pending') "S2 的草案回到 pending（有新未绑定合同用同一文本）"
    Assert-That ($null -ne $dCase2 -and [string]::IsNullOrEmpty($dCase2.matchedId)) "回归时清空 matched_id"
    Assert-That ($null -ne $dCase2 -and $dCase2.count -eq 1) "计数刷新为 1（只剩这一条未绑定）"
    $claimAgain = Claim-Draft $dCase2 @{ partyId = $pid1 } "认领(claimed 回归后)"
    Assert-That ($claimAgain.code -eq 200 -and $claimAgain.data.contractCount -eq 1) "再次认领绑定 1 条（并复用已有档案，不新建）"
    Assert-That ((SqlScalar ("select supplier_id from t_ctms_contract where id = '" + $c3.id + "'")) -eq $pid1) "新合同被绑定到同一个档案"

    # ---- S10/S11：ignored 不复活 + 已认领拒绝文案 ----
    Write-Host ""
    Write-Host "[S10/S11] ignored 不复活；已认领拒绝含「已认领」" -ForegroundColor Yellow
    $ig = New-Fixture 'PURZC202610' 'PUR' 'CTMMIG 我方' $T_IGN '13'
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dIgn = DraftOf 'supplier' $T_IGN
    Assert-That ($null -ne $dIgn) "忽略用例的草案已生成"
    $ign = Api 'POST' ("/ctms/migration/drafts/" + $dIgn.id + "/ignore?remark=" + [uri]::EscapeDataString('迁移验收：忽略')) $null
    Assert-That ($ign.data.status -eq 'ignored') "忽略后状态=ignored"
    $null = Api 'POST' '/ctms/migration/scan' $null
    $dIgn2 = DraftOf 'supplier' $T_IGN
    Assert-That ($null -ne $dIgn2 -and $dIgn2.status -eq 'ignored') "再扫描后仍是 ignored（不复活）"
    # 注：「已认领不得重复认领」的断言**不放在这里**：这一段之前跑过 scan，草案可能已按 7.2 的
    #     回归规则回到 pending，在此断言会假红（t26 实测踩过）。该语义由 JUnit
    #     （认领新建供应商档案并批量绑定四份合同 → 已认领草案不可重复认领）与 S9 的"复用同一档案"覆盖，
    #     真库侧的重复认领拒绝见 t25 演练记录（连提两次均含「已认领」）。

    # ---- S12：口径自诊断（计数>0 而实绑 0 必须永不出现） ----
    Write-Host ""
    Write-Host "[S12] 口径自诊断：不允许出现「计数>0 而实绑 0」" -ForegroundColor Yellow
    # 「草案声称有 N 份合同用这个文本、认领却一份都没绑上」是口径不一致的**症状形态**（t16 F1）。
    # 直接按"是否存在 claimed 且 contract_count = 0 的夹具草案"判：一条 SQL 说清，也不受后续 scan 影响。
    $zeroClaimed = '0'
    if ($script:DraftIds.Count -gt 0) {
        $zeroClaimed = SqlScalar ("select count(*) from t_ctms_party_draft where id in ('" + (($script:DraftIds | Select-Object -Unique) -join "','") + "') and status = 'claimed' and contract_count = 0")
    }
    Assert-That ($zeroClaimed -eq '0') "没有任何一条 claimed 草案的计数为 0（口径不一致的症状形态）"
}
finally {
    Write-Host ""
    Write-Host "[收尾] 清理夹具（按显式 id 清单，幂等）" -ForegroundColor Yellow
    try { Clear-Fixtures } catch { Bad ("清理夹具失败：" + $_.Exception.Message) }
    Assert-That ((SqlScalar ("select count(*) from t_ctms_contract where id in ('" + (($script:ContractIds | Select-Object -Unique) -join "','") + "')")) -eq '0' -or $script:ContractIds.Count -eq 0) `
        "夹具合同已全部删除"
    Assert-That ((SqlScalar ("select count(*) from t_ctms_party_draft where id in ('" + (($script:DraftIds | Select-Object -Unique) -join "','") + "')")) -eq '0' -or $script:DraftIds.Count -eq 0) `
        "夹具草案已全部删除"
    Assert-That ((SqlScalar ("select count(*) from t_ctms_supplier where id in ('" + (($script:PartyIds | Select-Object -Unique) -join "','") + "')")) -eq '0' -or $script:PartyIds.Count -eq 0) `
        "认领新建/预置的档案已全部删除"
    Remove-Item $script:LockFile -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail) -ForegroundColor Cyan
if ($script:Fail -gt 0) {
    Write-Host "失败清单：" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host ("    - " + $_) -ForegroundColor Red }
    exit 1
}
exit 0
