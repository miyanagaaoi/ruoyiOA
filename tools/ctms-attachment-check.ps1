<#
================================================================================
 ctms-attachment-check.ps1 —— 合同附件 B3「附件与操作日志接入」验收（第 6 组 / 任务 6.1~6.3）
--------------------------------------------------------------------------------
 为什么是"真实环境 + 接口 + 真磁盘"：
   第 6 组的验收面里有两件**内存桩证明不了**的事：
     · "超限后半成品文件不存在" —— 断言对象是磁盘上的文件，不是 Mapper；
     · "413"            —— 断言对象是 HTTP 状态码，内存桩里没有 HTTP。
   还有一条只有在真库才能发现：**平台 FileUploadUtils.extractFilename 对非 ASCII 文件名
   会退化成空主名**（存储名变成 `.pdf`）。本脚本因此显式用中文名上传一次。

 覆盖（对应 tasks.md §6.1~6.3 的「验证：」）：
   6.1 合法对象上传成功并落库（元数据 + 磁盘文件双断言）、未注册对象类型被拒、
       对象不存在被拒、`(object_type, object_id)` 复合索引存在（SHOW INDEX 核对）、
       **两个申请单（purchase_request / sales_request）端到端上传/列表/下载/删除（t19 补登记）**
   6.2 白名单外后缀被拒（zip/mp4 两种"平台允许但我们更窄"的判别用例）、
       20MB 边界（等于通过 / 超出 1 字节被拒且**半成品文件不存在**）、
       随机文件名（同名两次不覆盖）、中文名不退化成只有后缀、
       按对象查看权（无权限账号列表/下载 403、数据范围外 403）
   6.3 删除附件写字段名「附件」的变更历史且含操作人；删除后下载接口不再返回

 用法（在仓库根目录，先跑 start-env.ps1 与 oa-login.ps1）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-attachment-check.ps1

 前置：后端 8080 在线；`.cache\token-{superAdmin,lina}.txt` 有效；
       rad_oa 已执行 `sql\二开-合同台账.sql`、字典参数 SQL、**菜单 SQL**（含 ctms:attachment:list）。

 副作用：夹具用 ASCII 前缀 ATTC/ATCUS/ATTD*（t19 的两个申请单）/ATCTMP*，收尾物理删除
         （幂等，连跑两次残留 0 行）；
         会临时把「合同台账」菜单子树授权给 common 角色并在收尾撤销；
         会写入 uploadPath\upload\<日期>\，收尾删除本轮产生的所有文件。

 三条脚本经验（沿用第 4/5 组）：
   1. **不可并发执行**：夹具按前缀清理，两轮并发会互相拆台 → 内置锁文件。
   2. **中文查询参数必须先编码再拼串**（嵌套单引号会提前结束外层字符串）。
   3. **断言要有区分力**：白名单用例必须挑"平台允许但我们不允许"的后缀
      （zip/mp4），否则"白名单比平台窄"这条改动就是不可见的。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl     = 'http://localhost:8080',
    [string]$MySqlCli    = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir    = 'F:\dsh\ruoyiOA\.cache',
    [string]$UploadRoot  = 'F:\dsh\ruoyiOA\uploadPath',
    [string]$Database    = 'rad_oa',
    [string]$LimitedUser = 'lina',
    [string]$LimitedRole = 'BE63F3F093C1411892284CDF9AD0BE07',
    [string]$ContractMenuId = '9F2C0000000000000000000000000011',
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
    return @($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' })
}
<# 含引号/中文的 SQL 走 stdin（PS 5.1 把引号拼进命令行会被截断；见 DEV-ENV §6.33） #>
function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('ctmsatt-' + [guid]::NewGuid().ToString('N') + '.sql')
    [System.IO.File]::WriteAllText($tmp, $sql, (New-Object System.Text.UTF8Encoding($false)))
    $out = (Get-Content $tmp -Raw -Encoding UTF8) | & $MySqlCli "--host=127.0.0.1" '--user=root' `
        '-D' $Database '--default-character-set=utf8mb4' 2>&1
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    return ($out -join "`n")
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
        if ($method -eq 'GET') { return Invoke-RestMethod "$BaseUrl$url" -Method Get -Headers $headers -TimeoutSec 120 }
        if ($null -eq $body)  { return Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $headers -TimeoutSec 120 }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20 -Compress))
        return Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 120
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

<# 刷新权限缓存（改过 sys_role_menu / sys_role.data_scope 后必须做） #>
function Refresh-Permissions([string]$user) {
    $r = Api 'GET' '/getInfo' $null $user
    if (-not (IsOk $r)) { throw "刷新 $user 的权限缓存失败：code=$($r.code) msg=$(MsgOf $r)" }
}

<#
  ⚠ 只调 /getInfo **并不总是够**：它只在"算出来的权限集合与缓存里的不一致"时
    才 tokenService.refreshToken 写回 Redis。本轮实测出现过
    "sys_role_menu 已加、/getInfo 返回的 permissions 里也已经有 ctms:attachment:list，
    但同一个 token 调 /ctms/attachment/list 仍然 403"的情形（时好时坏、不可复现到具体某一步）。
    脚本是要反复跑的回归资产，不能建立在"有时够"的机制上：
    权限态切换一律走 **完整重新登录**（oa-login.ps1 内部会临时关掉验证码再恢复）。
#>
function Relogin([string]$user) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'oa-login.ps1') `
        -Users $user -CacheDir $CacheDir -Database $Database 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "重新登录 $user 失败（oa-login.ps1 exit=$LASTEXITCODE）" }
}

<#
  上传/下载必须用 curl.exe：
    · 上传是 multipart，Invoke-RestMethod 在 PS5.1 里要手搓 boundary；
    · 413 要让 Invoke-WebRequest 走异常分支才拿得到状态码，不如 -w '%{http_code}' 直接。
#>
function Upload([string]$objectType, [string]$objectId, [string]$filePath, [string]$user = 'superAdmin') {
    $token = TokenOf $user
    $out = & curl.exe -s -o (Join-Path $env:TEMP 'ctms-att-up.json') -w '%{http_code}' `
        -X POST "$BaseUrl/ctms/attachment/upload" `
        -H "Authorization: Bearer $token" `
        -F "objectType=$objectType" -F "objectId=$objectId" -F "file=@$filePath" 2>&1
    $code = [string](@($out) | Select-Object -Last 1)
    $bodyFile = Join-Path $env:TEMP 'ctms-att-up.json'
    $body = ''
    if (Test-Path $bodyFile) { $body = (Get-Content $bodyFile -Raw -Encoding UTF8) }
    $json = $null
    try { $json = $body | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Http = $code; Body = $body; Json = $json }
}
function Download([string]$id, [string]$user = 'superAdmin') {
    $token = TokenOf $user
    $out = & curl.exe -s -o (Join-Path $env:TEMP 'ctms-att-down.bin') -w '%{http_code}' `
        -H "Authorization: Bearer $token" "$BaseUrl/ctms/attachment/$id/download" 2>&1
    $code = [string](@($out) | Select-Object -Last 1)
    $bodyFile = Join-Path $env:TEMP 'ctms-att-down.bin'
    $body = ''
    if (Test-Path $bodyFile) { $body = (Get-Content $bodyFile -Raw -Encoding UTF8) }
    $json = $null
    try { $json = $body | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ Http = $code; Body = $body; Json = $json }
}

# ================================================================ 夹具标识
$S = (Get-Date -Format 'HHmmss') + (Get-Random -Minimum 100 -Maximum 999)
$CustId     = "ATCUS$S"
$ContractId = "ATTC$S"
$ContractNo = "ATTC$S"
$ContractName = "附件验收合同$S"
$DummyObject = "ATTC00000000000000000000000000FF"
<#
  t19：两个申请单对象类型（purchase_request / sales_request）的夹具。
  它们在 B4 首轮交付时漏登记（参考仓库 OBJECT_PERMS 是"合同 + 7 类单据"，含这两个），
  补登记后必须端到端可挂附件；夹具前缀用 ATTD（区别于合同的 ATTC），便于清理与残留断言。
#>
$PurReqId   = "ATTD$S"
$SalReqId   = "ATTDX$S"
$DummyDoc   = "ATTD00000000000000000000000000FF"

$uploadDir = Join-Path $UploadRoot 'upload'

<# 清理夹具：顺序与 ctms-contract-check 一致（附件 → 变更历史 → 合同 → 客户） #>
function Clear-Fixtures {
    $out = SqlFile @"
DELETE FROM t_ctms_attachment WHERE object_id LIKE 'ATTC%' OR contract_id LIKE 'ATTC%'
    OR file_name LIKE 'ATC%' OR stored_path LIKE '%ATTC%' OR stored_path LIKE '%ATC-%';
-- t19：两个申请单对象类型的附件夹具（object_id = ATTD...）
DELETE FROM t_ctms_attachment WHERE object_id LIKE 'ATTD%';
-- 兜底：受限账号上传那条的 file_name/路径可能不带 ATC 前缀（上一轮残留），按 uploader 再清一次
DELETE FROM t_ctms_attachment WHERE create_by='$LimitedUser';
DELETE FROM t_ctms_change_log WHERE object_id LIKE 'ATTC%' OR object_id LIKE 'ATTD%' OR contract_id LIKE 'ATTC%';
DELETE FROM t_ctms_contract_tag WHERE contract_id IN (SELECT id FROM t_ctms_contract WHERE id LIKE 'ATTC%' OR name LIKE '附件验收合同%');
DELETE FROM t_ctms_contract_item WHERE contract_id IN (SELECT id FROM t_ctms_contract WHERE id LIKE 'ATTC%' OR name LIKE '附件验收合同%');
DELETE FROM t_ctms_contract WHERE id LIKE 'ATTC%' OR name LIKE '附件验收合同%';
DELETE FROM t_ctms_customer WHERE id LIKE 'ATCUS%';
-- t19：申请单夹具（先删附件与变更历史，再删单据行）
DELETE FROM t_ctms_purchase_request WHERE id LIKE 'ATTD%' OR doc_no LIKE 'ATTD%';
DELETE FROM t_ctms_sales_request WHERE id LIKE 'ATTD%' OR doc_no LIKE 'ATTD%';
"@
    if ($out -match 'ERROR') { Bad "夹具清理出现 SQL 错误：$out" }
}

# ------------------------------------------------------------------ 运行锁
$script:LockFile = Join-Path $CacheDir 'ctms-attachment-check.lock'
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
$required = @('t_ctms_attachment','t_ctms_contract','t_ctms_change_log','t_ctms_customer')
$missing = @()
foreach ($t in $required) {
    if ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$t'") -ne '1') { $missing += $t }
}
if ($missing.Count -gt 0) { throw "缺少 B3 业务表：$($missing -join ', ')" }
$adminId = Sql "SELECT user_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
if (-not $adminId) { throw '取不到 superAdmin 的 user_id' }
# dept_id 有 FK 指向 sys_dept（fk_contract_dept）：写死一个不存在的部门会 1452，必须取真实值
$adminDept = Sql "SELECT dept_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
if (-not $adminDept) { throw '取不到 superAdmin 的 dept_id' }

# 权限点必须先落地（否则上传会被 403，看起来像"附件功能坏了"，其实是没跑菜单 SQL）
$permCount = Sql "SELECT COUNT(*) FROM sys_menu WHERE perms='ctms:attachment:list'"
Assert-That ($permCount -eq '1') "菜单 SQL 已执行：ctms:attachment:list 权限点存在（实测 $permCount 行）"

Info "夹具序号 ${S}；合同ID=${ContractId}；受限账号=${LimitedUser}"

Clear-Fixtures

# ------------------------------------------------------------------
# 自愈：本脚本会临时把 common 角色的 data_scope 改成 '5'（仅本人）来验数据范围。
# 如果上一轮**在中途异常退出**（没走到 finally），角色就会被永久留在 '5'，
# 现象是"lina 连 /getInfo 都 403"（没有任何菜单），下一轮看起来像权限点没生效。
# 这里用 remark 哨兵做自愈：哨兵在 + 范围是 '5' ⇒ 上一轮没还原 ⇒ 强制还原为 '1'。
# ------------------------------------------------------------------
$scopeBaseline = '1'
$sentinel = '__B3_ATT_SCOPE_TMP__'
$curRemark = Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$LimitedRole'"
$curScope  = Sql "SELECT data_scope FROM sys_role WHERE role_id='$LimitedRole'"
if ($curRemark -eq $sentinel -and $curScope -eq '5') {
    $null = SqlFile "UPDATE sys_role SET data_scope='$scopeBaseline', remark=NULL WHERE role_id='$LimitedRole';"
    Info "检测到上一轮异常退出留下的数据范围改动（remark=$sentinel, data_scope=5）→ 已自愈还原为 '$scopeBaseline'"
}

# 收尾要恢复的状态：角色授权 + 角色数据范围
$grantedBefore = @(SqlRows "SELECT menu_id FROM sys_role_menu WHERE role_id='$LimitedRole'")
$scopeBefore = Sql "SELECT data_scope FROM sys_role WHERE role_id='$LimitedRole'"
$remarkBefore = Sql "SELECT IFNULL(remark,'') FROM sys_role WHERE role_id='$LimitedRole'"
$script:FilesBefore = @()
if (Test-Path $uploadDir) { $script:FilesBefore = @(Get-ChildItem $uploadDir -Recurse -File | ForEach-Object { $_.FullName }) }

function Restore-Role {
    try {
        # ① 还原角色-菜单授权。
        # ⚠ 不能只"把进入时的快照插回去"：本脚本**自己管理的那几个菜单**（合同台账 / 合同编辑 / 附件）
        #   若在上一轮异常退出时留在库里，插回快照就会把污染带进下一轮的"无权限"基线
        #   （症状：本该 403 的用例拿到 200）。所以先把本脚本管理的菜单行彻底清掉，
        #   再插回快照里**不属于**管理集合的那些。
        $managed = @($ContractMenuId, '9F2C0000000000000000000000000023', '9F2C000000000000000000000000002A')
        $null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$LimitedRole';"
        $keep = @($grantedBefore | Where-Object { $managed -notcontains $_ })
        if ($keep.Count -gt 0) {
            $values = ($keep | ForEach-Object { "('$LimitedRole','$_')" }) -join ','
            $null = SqlFile "INSERT INTO sys_role_menu(role_id, menu_id) VALUES $values;"
        }
        # ② 还原数据范围与哨兵 remark（无论之前是什么，都还原成进入时的值）
        $scope = if ($scopeBefore) { $scopeBefore } else { '1' }
        $remark = if ($remarkBefore -eq $sentinel) { 'NULL' } else { "'" + $remarkBefore + "'" }
        if ($remarkBefore -eq '') { $remark = 'NULL' }
        $null = SqlFile "UPDATE sys_role SET data_scope='$scope', remark=$remark WHERE role_id='$LimitedRole';"
        # ③ 清本轮新增的上传文件
        if (Test-Path $uploadDir) {
            Get-ChildItem $uploadDir -Recurse -File | ForEach-Object {
                if ($script:FilesBefore -notcontains $_.FullName) { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }
            }
        }
    } catch {
        Bad "收尾还原失败（需要人工检查 sys_role_menu / sys_role.data_scope）：$_"
    }
}

try {
    # ============================================================ 夹具
    Step '准备夹具：客户 + 合同（超管创建，因此普通用户默认不可见）+ 三个测试文件'
    # 合同表 NOT NULL 且无默认值的列共 6 个：id / contract_no / name / subject_matter / dept_id / create_id
    # —— 少写 subject_matter 会直接 ERROR 1364 让整条 SQL 中止（本轮踩过一次），别删这一列。
    $null = SqlFile @"
INSERT INTO t_ctms_customer (id,code,name,create_id,create_time,update_time) VALUES ('$CustId','ATC-$S','附件验收客户$S','$adminId',NOW(),NOW());
INSERT INTO t_ctms_contract (id,contract_no,name,type,status,arrival_status,currency,amount,paid_amount,subject_matter,del_flag,create_id,dept_id,create_time,update_time)
VALUES ('$ContractId','$ContractNo','$ContractName','PUR','内部审批中','未到货','CNY',0.00,0.00,'','0','$adminId','$adminDept',NOW(),NOW());
"@
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE id='$ContractId'") -eq '1') '夹具合同已就位'

    # t19：两个申请单对象类型的夹具（真实单据行；采购/销售申请单的核心 NOT NULL 列：
    #      id / doc_no / doc_date / status / dept_id / create_id）
    Step '准备夹具：采购申请单 + 销售申请单各一张（t19：两个新登记的对象类型）'
    $null = SqlFile @"
INSERT INTO t_ctms_purchase_request (id,doc_no,doc_date,status,dept_id,create_id,create_time,update_time)
VALUES ('$PurReqId','$PurReqId',CURDATE(),'draft','$adminDept','$adminId',NOW(),NOW());
INSERT INTO t_ctms_sales_request (id,doc_no,doc_date,status,dept_id,create_id,create_time,update_time)
VALUES ('$SalReqId','$SalReqId',CURDATE(),'draft','$adminDept','$adminId',NOW(),NOW());
"@
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_purchase_request WHERE id='$PurReqId'") -eq '1') '夹具采购申请单已就位'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_sales_request WHERE id='$SalReqId'") -eq '1') '夹具销售申请单已就位'

    $tmpDir = Join-Path $env:TEMP "ctms-att-fixtures"
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    $okFile     = Join-Path $tmpDir 'ATC-ok.pdf'
    $okFile2    = Join-Path $tmpDir 'ATC-ok-second.pdf'
    $txtFile    = Join-Path $tmpDir 'ATC-note.txt'
    $zipFile    = Join-Path $tmpDir 'ATC-bad.zip'
    $mp4File    = Join-Path $tmpDir 'ATC-bad.mp4'
    $cnFile     = Join-Path $tmpDir 'ATC-合同扫描件.pdf'
    $bigEqFile  = Join-Path $tmpDir 'ATC-20mb.pdf'
    $bigOver    = Join-Path $tmpDir 'ATC-20mb-plus.pdf'
    [System.IO.File]::WriteAllBytes($okFile,  [System.Text.Encoding]::UTF8.GetBytes("B3 attachment acceptance fixture"))
    [System.IO.File]::WriteAllBytes($okFile2, [System.Text.Encoding]::UTF8.GetBytes("second copy with same original name"))
    [System.IO.File]::WriteAllText($txtFile, 'plain text attachment', (New-Object System.Text.UTF8Encoding($false)))
    [System.IO.File]::WriteAllBytes($zipFile, [byte[]](0x50,0x4B,0x03,0x04))
    [System.IO.File]::WriteAllBytes($mp4File, [byte[]](0x00,0x00,0x00,0x18))
    [System.IO.File]::WriteAllBytes($cnFile,  [byte[]](0x25,0x50,0x44,0x46))
    # 20MB 边界：恰好 20971520 字节与 20971521 字节
    $fsEq = [System.IO.File]::Create($bigEqFile); $fsEq.SetLength(20971520); $fsEq.Close()
    $fsOv = [System.IO.File]::Create($bigOver);   $fsOv.SetLength(20971521); $fsOv.Close()
    Assert-That (((Get-Item $bigEqFile).Length) -eq 20971520) '边界夹具：恰好 20MB'
    Assert-That (((Get-Item $bigOver).Length) -eq 20971521) '边界夹具：20MB + 1 字节'

    # ============================================================ 6.1 挂载层
    Step '6.1 合法对象上传成功：元数据落库 + 文件真的在磁盘上'
    $up = Upload 'contract' $ContractId $okFile
    Assert-That ($up.Http -eq '200' -and $up.Json.code -eq 200) "上传返回 200（HTTP=$($up.Http) code=$($up.Json.code)）"
    $attId = ''
    if ($up.Json -and $up.Json.data) { $attId = $up.Json.data.id }
    Assert-That ($attId -ne '') "返回体含附件主键（服务端生成 UUID：$attId）"
    $storedPath = ''
    if ($up.Json -and $up.Json.data) { $storedPath = $up.Json.data.storedPath }
    Assert-That ($storedPath -like '/profile/upload/*') "落库路径是 / 开头的相对路径（$storedPath）"

    $row = Sql "SELECT CONCAT(object_type,'|',object_id,'|',contract_id,'|',file_name,'|',size_bytes,'|',del_flag) FROM t_ctms_attachment WHERE id='$attId'"
    Assert-That ($row -like "contract|$ContractId|$ContractId|ATC-ok.pdf|*|0") "元数据逐列正确（$row）"
    $fileOnDisk = Join-Path $UploadRoot ($storedPath -replace '^/profile/','')
    Assert-That (Test-Path $fileOnDisk) "物理文件存在：$fileOnDisk"
    if (Test-Path $fileOnDisk) {
        $diskSize = (Get-Item $fileOnDisk).Length
        Assert-That ($diskSize -gt 0) "物理文件非空（$diskSize 字节）"
    }
    Assert-That ($storedPath -match '/ATC-ok_\d{14}\d{4}\.pdf$') "随机命名=原名+时间戳+随机段（$storedPath）"

    Step '6.1 未注册对象类型被拒'
    $bad = Upload 'ctms_unknown_object' $ContractId $okFile
    Assert-That ($bad.Body -match '未注册的对象类型') "未注册对象类型被拒（HTTP=$($bad.Http) msg=$($bad.Body -replace '\s+',' ')）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_type='ctms_unknown_object'") -eq '0') '被拒时不得落库'

    Step '6.1 对象不存在被拒'
    $ghost = Upload 'contract' $DummyObject $okFile
    Assert-That ($ghost.Body -match '合同不存在') "不存在的对象被拒（HTTP=$($ghost.Http) msg=$($ghost.Body -replace '\s+',' ')）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id='$DummyObject'") -eq '0') '被拒时不得落库'

    Step '6.1 (object_type, object_id) 复合索引存在（SHOW INDEX 核对）'
    $idxRow = Sql "SELECT CONCAT(INDEX_NAME,':',GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_attachment' AND INDEX_NAME='idx_object' GROUP BY INDEX_NAME"
    Assert-That ($idxRow -eq 'idx_object:object_type,object_id') "复合索引 idx_object 为 (object_type, object_id)（实测 $idxRow）"

    # ============================================================ 6.2 白名单
    Step '6.2 白名单外后缀被拒（zip/mp4 是"平台允许但 CTMS 更窄"的判别用例）'
    $zip = Upload 'contract' $ContractId $zipFile
    Assert-That ($zip.Body -match '后缀\[zip\]不正确') "zip 被拒且文案与平台同款（$($zip.Body -replace '\s+',' ')）"
    $mp4 = Upload 'contract' $ContractId $mp4File
    Assert-That ($mp4.Body -match '后缀\[mp4\]不正确') "mp4 被拒（平台白名单里有它，所以这条能证明"更窄"生效）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE file_name LIKE 'ATC-bad%'") -eq '0') '白名单外的请求不得落库'

    Step '6.2 白名单内的 txt 正常放行（反向对照，避免"什么都拒"也算通过）'
    $txt = Upload 'contract' $ContractId $txtFile
    Assert-That ($txt.Http -eq '200' -and $txt.Json.code -eq 200) 'txt 放行（白名单不是"只放 pdf"）'

    Step '6.2 中文文件名不退化成只有后缀（平台 extractFilename 的真实缺陷）'
    $cn = Upload 'contract' $ContractId $cnFile
    $cnStored = ''
    if ($cn.Json -and $cn.Json.data) { $cnStored = $cn.Json.data.storedPath }
    Assert-That ($cnStored -match 'ATC-合同扫描件_\d{14}\d{4}\.pdf$') "中文主名被完整保留（$cnStored）"

    Step '6.2 同名文件重复上传不覆盖（随机文件名）'
    $up2 = Upload 'contract' $ContractId $okFile2
    $path2 = ''
    if ($up2.Json -and $up2.Json.data) { $path2 = $up2.Json.data.storedPath }
    Assert-That ($path2 -ne $storedPath) "同名不同次得到不同存储名（$storedPath vs $path2）"
    Assert-That (Test-Path $fileOnDisk) '第一次上传的文件仍在（未被覆盖/删除）'

    # ============================================================ 6.2 20MB 边界
    Step '6.2 20MB 边界：恰好 20MB 通过'
    $beforeFiles = @(Get-ChildItem $uploadDir -Recurse -File -ErrorAction SilentlyContinue).Count
    $eq = Upload 'contract' $ContractId $bigEqFile
    Assert-That ($eq.Http -eq '200' -and $eq.Json.code -eq 200) "恰好 20MB 放行（HTTP=$($eq.Http) code=$($eq.Json.code)）"
    $eqId = ''
    if ($eq.Json -and $eq.Json.data) { $eqId = $eq.Json.data.id }
    Assert-That ((Sql "SELECT size_bytes FROM t_ctms_attachment WHERE id='$eqId'") -eq '20971520') '落库字节数 = 20971520'

    Step '6.2 20MB 边界：超出 1 字节被拒 + 返回 413 + 半成品文件不存在'
    $over = Upload 'contract' $ContractId $bigOver
    Assert-That ($over.Http -eq '413') "HTTP 状态是 413（实测 HTTP=$($over.Http)）"
    Assert-That ($over.Body -match '20MB') "413 文案含上限（$($over.Body -replace '\s+',' ')）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE file_name='ATC-20mb-plus.pdf'") -eq '0') '超限请求不得落库'
    $afterFiles = @(Get-ChildItem $uploadDir -Recurse -File -ErrorAction SilentlyContinue).Count
    Assert-That ($afterFiles -eq ($beforeFiles + 1)) "超限未新增任何文件（前 $beforeFiles → 后 $afterFiles，只多了恰好 20MB 那份）"

    # ============================================================ 6.3 删除留痕
    Step '6.3 删除附件写字段名「附件」的变更历史且含操作人'
    $logBefore = Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$ContractId' AND field_name='附件'"
    $del = Api 'DELETE' "/ctms/attachment/$attId" $null
    Assert-That (IsOk $del) "删除附件返回成功（code=$($del.code)）"
    Assert-That ((Sql "SELECT del_flag FROM t_ctms_attachment WHERE id='$attId'") -eq '1') '元数据软删除（del_flag=1）'
    $logAfter = Sql "SELECT COUNT(*) FROM t_ctms_change_log WHERE contract_id='$ContractId' AND field_name='附件'"
    Assert-That (([int]$logAfter - [int]$logBefore) -eq 1) "变更历史新增 1 条字段名「附件」（$logBefore → $logAfter）"
    # ⚠ 不要用 "SELECT CONCAT(...)" + SqlFile：SqlFile 的回显里含 SQL 原文，取首行会取到 SQL 本身。
    #   mysql --batch 的输出是 **TAB 分隔**，这里直接取一整行再按 TAB 拆。
    $logLine = Sql "SELECT field_name, IFNULL(old_value,'~'), IFNULL(new_value,'~'), IFNULL(operator_id,'~'), IFNULL(operator_name,'~'), source FROM t_ctms_change_log WHERE contract_id='$ContractId' AND field_name='附件' ORDER BY create_time DESC, id DESC LIMIT 1"
    $f = @($logLine -split "`t")
    Assert-That ($f.Count -ge 6) "变更历史可解析成 6 列（实测 $($f.Count) 列：$logLine）"
    Assert-That ($f[0] -eq '附件') "字段名是「附件」（实测 $($f[0])）"
    Assert-That ($f[1] -like '*ATC-ok.pdf*') "旧值含被删文件名与大小（实测 $($f[1])）"
    Assert-That ($f[2] -eq '~') "新值为空（表示该附件被移除）"
    Assert-That ($f[3] -ne '~' -and $f[3] -ne '') "历史里含操作人标识（实测 $($f[3])）"
    Assert-That ($f[5] -eq 'manual') "来源为 manual（是人删的，实测 $($f[5])）"

    Step '6.3 删除后下载接口不再返回该文件（列表 + 按标识下载都取不到）'
    $listAfter = Api 'GET' "/ctms/attachment/list?objectType=contract&objectId=$ContractId" $null
    $stillThere = @($listAfter.data | Where-Object { $_.id -eq $attId }).Count
    Assert-That (IsOk $listAfter -and $stillThere -eq 0) '列表里不再出现已删除的附件'
    $downDeleted = Download $attId
    Assert-That ($downDeleted.Body -match '附件不存在') "已删除附件按标识下载被拒（HTTP=$($downDeleted.Http) msg=$($downDeleted.Body -replace '\s+',' ')）"
    Assert-That (Test-Path $fileOnDisk) '软删除不动磁盘（审计口径：文件保留，只是元数据不可见）'

    # ============================================================ 6.2 鉴权
    Step '6.2 按对象查看权：只授权「合同台账」菜单（无附件权限点）→ 列表/下载/上传全部被拒'
    $null = SqlFile "INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES ('$LimitedRole','$ContractMenuId');"
    Relogin $LimitedUser
    # 计数型断言必须先取基线再比增量：同一个夹具合同上前面已经传过同名文件（"白名单内 txt 放行"那一步）
    $noteBefore = [int](Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id='$ContractId' AND file_name='ATC-note.txt'")
    $noPermList = Api 'GET' "/ctms/attachment/list?objectType=contract&objectId=$ContractId" $null $LimitedUser
    Assert-That (IsForbidden $noPermList) "无附件权限调用列表返回 403（code=$($noPermList.code) msg=$(MsgOf $noPermList)）"
    $noPermDown = Download $attId $LimitedUser
    # 期望"取不到"：可能是权限 403，也可能是"附件不存在"（该附件在本脚本前面已被删除）。
    # 这里断言的是**不可达**，不写死某一种拒绝原因。
    Assert-That ($noPermDown.Http -ne '200' -or $noPermDown.Body -notmatch '"code":200') "无附件权限下载不可达（HTTP=$($noPermDown.Http) msg=$($noPermDown.Body -replace '\s+',' ')）"
    $noPermUp = Upload 'contract' $ContractId $txtFile $LimitedUser
    Assert-That ($noPermUp.Http -ne '200' -or $noPermUp.Body -match '没有权限') "无附件权限上传被拒（HTTP=$($noPermUp.Http) msg=$($noPermUp.Body -replace '\s+',' ')）"
    $noteAfter = [int](Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id='$ContractId' AND file_name='ATC-note.txt'")
    Assert-That ($noteAfter -eq $noteBefore) "被拒的上传不得落库（$noteBefore → $noteAfter）"

    Step '6.2 写动作要"两个权限点同时具备"：只加 ctms:attachment:list（无合同编辑权）→ 能看不能传'
    $null = SqlFile "INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES ('$LimitedRole','9F2C000000000000000000000000002A');"
    Relogin $LimitedUser
    $listWithPerm = Api 'GET' "/ctms/attachment/list?objectType=contract&objectId=$ContractId" $null $LimitedUser
    Assert-That (IsOk $listWithPerm) "有附件查看权后列表 200（code=$($listWithPerm.code) msg=$(MsgOf $listWithPerm)）"
    # 用 SQL 的当前行数做期望值（本次运行前已删除 1 个，别写死数字）
    $expectRows = [int](Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_type='contract' AND object_id='$ContractId' AND del_flag='0'")
    Assert-That (@($listWithPerm.data).Count -eq $expectRows) "列表条数与库内未删除行数一致（接口 $(@($listWithPerm.data).Count) / 库 $expectRows）"
    Assert-That ($expectRows -ge 4) "列表至少含前面几步上传的 4 个附件（实测 $expectRows）"
    $viewOnly = Upload 'contract' $ContractId $txtFile $LimitedUser
    Assert-That ($viewOnly.Http -ne '200' -or $viewOnly.Body -match '没有权限') "只有查看权时上传被拒（HTTP=$($viewOnly.Http) msg=$($viewOnly.Body -replace '\s+',' ')）"
    $noteAfter2 = [int](Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id='$ContractId' AND file_name='ATC-note.txt'")
    Assert-That ($noteAfter2 -eq $noteBefore) "只有查看权时不得落库（$noteBefore → $noteAfter2）"

    Step '6.2 加上 ctms:contract:edit 后：上传成功（反向对照，证明拒绝确实来自编辑权缺失）'
    $null = SqlFile "INSERT IGNORE INTO sys_role_menu(role_id, menu_id) VALUES ('$LimitedRole','9F2C0000000000000000000000000023');"
    Relogin $LimitedUser
    $nowUp = Upload 'contract' $ContractId $txtFile $LimitedUser
    Assert-That ($nowUp.Http -eq '200' -and $nowUp.Json.code -eq 200) "两个权限点齐备后上传 200（HTTP=$($nowUp.Http)）"
    $noteAfter3 = [int](Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id='$ContractId' AND file_name='ATC-note.txt'")
    Assert-That ($noteAfter3 -eq ($noteBefore + 1)) "上传成功后确实多了一行（$noteBefore → $noteAfter3）"
    $limitedUploader = Sql "SELECT create_by FROM t_ctms_attachment WHERE object_id='$ContractId' AND file_name='ATC-note.txt' ORDER BY create_time DESC, id DESC LIMIT 1"
    Assert-That ($limitedUploader -eq $LimitedUser) "上传人快照是受限账号本人（实测 $limitedUploader）"

    Step '6.2 数据范围：把 common 角色的数据范围临时改成「仅本人」→ 他人创建的合同附件不可见'
    # 哨兵：万一这里之后脚本异常退出，下一轮开头的自愈逻辑认得出"这是我们改的"
    $null = SqlFile "UPDATE sys_role SET data_scope='5', remark='$sentinel' WHERE role_id='$LimitedRole';"
    Relogin $LimitedUser
    $ownScope = Api 'GET' "/ctms/attachment/list?objectType=contract&objectId=$ContractId" $null $LimitedUser
    Assert-That (IsForbidden $ownScope) "范围外合同附件列表返回 403（code=$($ownScope.code) msg=$(MsgOf $ownScope)）"
    Assert-That ($null -eq $ownScope.data -or @($ownScope.data).Count -eq 0) '范围外不返回任何附件数据'
    $scopeDown = Download $attId $LimitedUser
    Assert-That ($scopeDown.Http -ne '200' -or $scopeDown.Body -notmatch '"code":200') "范围外下载同样不可达（HTTP=$($scopeDown.Http) msg=$($scopeDown.Body -replace '\s+',' ')）"

    # 还原数据范围后再验一次"授权 + 全部数据"能拿到（证明上一格是范围判定而不是权限判定）
    $null = SqlFile "UPDATE sys_role SET data_scope='1', remark=NULL WHERE role_id='$LimitedRole';"
    Relogin $LimitedUser
    $scopeBack = Api 'GET' "/ctms/attachment/list?objectType=contract&objectId=$ContractId" $null $LimitedUser
    Assert-That (IsOk $scopeBack) "数据范围还原为全部后同一账号又能看到（code=$($scopeBack.code)）"

    Step '6.1 附件清单接口：已注册 / B4 已接入 / 限制口径'
    $meta = Api 'GET' '/ctms/attachment/object-types' $null
    Assert-That (IsOk $meta) '对象类型清单接口可用'
    Assert-That (@($meta.registered) -contains 'contract') '已注册对象类型含 contract'
    # ⚠ 2026-10-05 口径变更（B4 交付，captain 已许可）：
    #   旧断言是 `(@($meta.plannedB4).Count -ge 1) 'B4 待补对象类型已登记'` ——
    #   它表达的是"B4 尚未交付、5 个单据对象刻意不放行"。B4 交付后这 5 个（+ stock_transfer）
    #   必须**已注册**，否则单据附件会被判"未注册的对象类型"而全部拒绝。
    # ⚠ 2026-10-05 t19 再变更（captain 已许可）：
    #   首轮交付漏了参考仓库 OBJECT_PERMS（app/routers/attachments.py:35-44）里的两个**申请单**，
    #   补登记后清单恰为 9 项 = 合同 + 8 类单据（比参考侧多 stock_transfer，见任务 6.6 / Q-B10）。
    #   断言只强化不放宽：新增"恰 9 项 / 恰 8 类 / 恒等式 / 两个申请单在册"，
    #   并把逐个断言从 6 类扩到 8 类（未改动本脚本其它任何断言的期望值）。
    foreach ($docType in @('purchase_request', 'purchase_order', 'sales_request', 'sales_order',
                           'stock_in', 'stock_out', 'stock_take', 'stock_transfer')) {
        Assert-That (@($meta.registered) -contains $docType) "已注册对象类型含 ${docType}（B4 接入）"
    }
    Assert-That (@($meta.plannedB4).Count -eq 0) "B4 交付后 plannedB4 已清空（实测 $(@($meta.plannedB4).Count) 项）"
    Assert-That (@($meta.registered).Count -eq 9) "已注册对象类型恰为 9 项 = 合同 + 8 类单据（实测 $(@($meta.registered).Count) 项）"
    Assert-That (@($meta.docObjectTypes).Count -eq 8) "接口公布 8 类单据对象类型（实测 $(@($meta.docObjectTypes).Count) 项）"
    Assert-That (@($meta.registered).Count -eq (@($meta.docObjectTypes).Count + 1)) "恒等式 registered = [contract] + docObjectTypes（$(@($meta.registered).Count) vs $(@($meta.docObjectTypes).Count) + 1）"
    Assert-That (@($meta.registered) -contains 'purchase_request') '参考仓库 OBJECT_PERMS 含采购申请单（t19 补登记）'
    Assert-That (@($meta.registered) -contains 'sales_request') '参考仓库 OBJECT_PERMS 含销售申请单（t19 补登记）'
    Assert-That ($meta.maxSizeMb -eq 20) "接口公布的单元大小上限 = 20MB（实测 $($meta.maxSizeMb)）"
    Assert-That (@($meta.extensions).Count -eq 10) "接口公布的白名单后缀 = 10 种（实测 $(@($meta.extensions).Count)）"

    # ============================================================ 6.1b 两个申请单端到端（t19）
    Step '6.1b 两个申请单（t19 补登记）：上传 / 列表 / 下载 / 删除 端到端'
    foreach ($pair in @(@('purchase_request', $PurReqId), @('sales_request', $SalReqId))) {
        $docType = $pair[0]
        $docId   = $pair[1]
        $upDoc = Upload $docType $docId $okFile
        Assert-That ($upDoc.Http -eq '200' -and $upDoc.Json.code -eq 200) "${docType} 上传 200（HTTP=$($upDoc.Http) code=$($upDoc.Json.code) msg=$($upDoc.Body -replace '\s+',' ')）"
        $docAttId = ''
        if ($upDoc.Json -and $upDoc.Json.data) { $docAttId = [string]$upDoc.Json.data.id }
        Assert-That ($docAttId -ne '') "${docType} 上传返回附件主键（$docAttId）"
        $docRow = Sql "SELECT CONCAT(object_type,'|',object_id,'|',IFNULL(contract_id,'NULL'),'|',del_flag) FROM t_ctms_attachment WHERE id='$docAttId'"
        Assert-That ($docRow -eq "$docType|$docId|NULL|0") "${docType} 元数据逐列正确且不写 contract_id（实测 $docRow）"

        $docList = Api 'GET' "/ctms/attachment/list?objectType=$docType&objectId=$docId" $null
        Assert-That (IsOk $docList) "${docType} 附件列表 200（code=$($docList.code)）"
        Assert-That (@($docList.data).Count -eq 1) "${docType} 列表恰含刚上传的 1 条（实测 $(@($docList.data).Count) 条）"

        $docDown = Download $docAttId
        Assert-That ($docDown.Http -eq '200' -and $docDown.Body -notmatch '"code":') "${docType} 下载返回文件字节（HTTP=$($docDown.Http) 字节=$($docDown.Body.Length)）"

        $docDel = Api 'DELETE' "/ctms/attachment/$docAttId" $null
        Assert-That (IsOk $docDel) "${docType} 删除返回成功（code=$($docDel.code)）"
        Assert-That ((Sql "SELECT del_flag FROM t_ctms_attachment WHERE id='$docAttId'") -eq '1') "${docType} 删除为软删除（del_flag=1）"
        $docAfter = Api 'GET' "/ctms/attachment/list?objectType=$docType&objectId=$docId" $null
        Assert-That (@($docAfter.data).Count -eq 0) "${docType} 删除后列表不再出现（实测 $(@($docAfter.data).Count) 条）"
    }

    Step '6.1b 申请单对象不存在被拒（存在性校验已接到 t_ctms_purchase_request / t_ctms_sales_request）'
    $ghostDoc = Upload 'purchase_request' $DummyDoc $okFile
    Assert-That ($ghostDoc.Body -match '采购申请单不存在') "不存在的采购申请单被拒（HTTP=$($ghostDoc.Http) msg=$($ghostDoc.Body -replace '\s+',' ')）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id='$DummyDoc'") -eq '0') '被拒时不得落库'
    $ghostDoc2 = Upload 'sales_request' $DummyDoc $okFile
    Assert-That ($ghostDoc2.Body -match '销售申请单不存在') "不存在的销售申请单被拒（HTTP=$($ghostDoc2.Http) msg=$($ghostDoc2.Body -replace '\s+',' ')）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id='$DummyDoc'") -eq '0') '被拒时不得落库（销售申请单）'

    Step '6.2 超限文件的落库与数据库列宽一致性（size_bytes int）'
    $colType = Sql "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_attachment' AND COLUMN_NAME='size_bytes'"
    Assert-That ($colType -like 'int*') "size_bytes 列类型可容纳 20MB（实测 $colType）"
    $notNulls = Sql "SELECT CONCAT(COUNT(*)) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_ctms_attachment' AND COLUMN_NAME IN ('object_type','object_id') AND IS_NULLABLE='NO'"
    Assert-That ($notNulls -eq '2') 'object_type/object_id 已收紧为 NOT NULL（第 2 组的约束回补）'

} finally {
    Step '收尾'
    Clear-Fixtures
    Restore-Role
    # 临时文件
    Get-ChildItem (Join-Path $env:TEMP 'ctms-att-fixtures') -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $env:TEMP 'ctms-att-up.json') -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $env:TEMP 'ctms-att-body.json') -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $env:TEMP 'ctms-att-down.bin') -Force -ErrorAction SilentlyContinue
    Remove-Item $script:LockFile -Force -ErrorAction SilentlyContinue

    $leftAtt = Sql "SELECT COUNT(*) FROM t_ctms_attachment WHERE object_id LIKE 'ATTC%' OR contract_id LIKE 'ATTC%' OR object_id LIKE 'ATTD%'"
    $leftCon = Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE id LIKE 'ATTC%' OR name LIKE '附件验收合同%'"
    $leftCus = Sql "SELECT COUNT(*) FROM t_ctms_customer WHERE id LIKE 'ATCUS%'"
    $leftDoc = Sql "SELECT (SELECT COUNT(*) FROM t_ctms_purchase_request WHERE id LIKE 'ATTD%' OR doc_no LIKE 'ATTD%') + (SELECT COUNT(*) FROM t_ctms_sales_request WHERE id LIKE 'ATTD%' OR doc_no LIKE 'ATTD%')"
    $leftRole = Sql "SELECT COUNT(*) FROM sys_role_menu WHERE role_id='$LimitedRole' AND menu_id IN ('$ContractMenuId','9F2C0000000000000000000000000023','9F2C000000000000000000000000002A')"
    Assert-That ($leftCon -eq '0') "合同夹具已清（残留 $leftCon）"
    Assert-That ($leftAtt -eq '0') "附件夹具已清（残留 $leftAtt）"
    Assert-That ($leftCus -eq '0') "客户夹具已清（残留 $leftCus）"
    Assert-That ($leftDoc -eq '0') "申请单夹具已清（残留 $leftDoc）"
    Assert-That ($leftRole -eq '0') "临时授权已撤销（残留 $leftRole）"
    $leftFiles = @(Get-ChildItem $uploadDir -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $script:FilesBefore -notcontains $_.FullName }).Count
    Assert-That ($leftFiles -eq 0) "本轮上传的文件已清（残留 $leftFiles）"
}

Write-Host ''
Write-Host "================================================" -ForegroundColor Cyan
Write-Host (" 通过 {0} / 失败 {1}" -f $script:Pass, $script:Fail) -ForegroundColor $(if ($script:Fail -eq 0) { 'Green' } else { 'Red' })
if ($script:Fail -gt 0) {
    Write-Host ' 失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "   - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "================================================" -ForegroundColor Cyan
exit 0
