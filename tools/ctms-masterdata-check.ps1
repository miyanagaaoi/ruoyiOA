<#
================================================================================
 ctms-masterdata-check.ps1 —— 合同台账 B3「主数据档案」验收（第 3 组 / 任务 3.1~3.5）
--------------------------------------------------------------------------------
 为什么是"真实环境 + 接口"：
   第 3 组的验收面几乎全是**接口行为**（重复编码被拒的提示文案、简称必填、
   停用后不进选择器、有引用时禁止删除、不做数据范围隔离），
   内存桩单测只能证明"服务层规则写了"，证明不了"接口真的这样返回"，
   更证明不了"Mapper XML 真能被 MyBatis 绑定、SQL 列名真与 DDL 对齐"。
   所以本脚本按 DEV-ENV.md §7 第 14 条的口径，用**登录后的 token** 打真实接口。

 ⚠ 一条重要的实现事实（本脚本第一版就栽在这里）：
   主键 id **由服务端生成**（`IdUtils.fastSimpleUUID()`），请求体里带 id **会被忽略**。
   因此夹具的 id 一律"先 POST 再用 code/name 反查"取回，**不要假设自己传的 id 生效** ——
   否则会出现"接口返回 200、断言却查不到行"的假失败，并连带污染后续用例。

 覆盖（对应规格 specs/ctms/business-partners/spec.md 与第 3 组任务）：
   3.1 客户档案：编码/名称唯一、简称可为空、CRUD
   3.2 供应商档案：简称必填（新增 + 修改两条路径）、账期非负
   3.3 物料域：类型树 5 级上限、同父同名唯一、叶子才可挂物料、
       单位小数位 0~4 且被引用禁删、仓库编码唯一、物料编码唯一与负价拒绝
   3.4 启停用与引用保护：停用不进"选择器"（/options）但管理列表仍可见、
       有引用时禁止删除（用真实合同行做引用）、零引用可删
   3.5 不做数据范围隔离：临时给低权限角色挂上「往来单位」菜单后能看到他人创建的档案；
       摘掉菜单后立即 403

 用法（在仓库根目录，先跑 start-env.ps1 与 oa-login.ps1）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\ctms-masterdata-check.ps1

 前置：
   · 后端 8080 在线；.cache\token-<user>.txt 有效
   · rad_oa 已执行 `sql\二开-合同台账.sql`（13 张 t_ctms_* 表）与菜单 SQL

 副作用：
   · 新建一批 **ASCII 前缀 CMD…** 的档案/类型/单位/仓库/物料夹具 + 1 份合同（仅用于引用保护），
     收尾按 code/name 白名单全部物理删除（脚本幂等，可反复跑）。
   · 会临时给测试角色挂/摘「往来单位」菜单（`sys_role_menu`），收尾还原。
   · ⚠ 夹具只落在本变更集**新建**的表上，绝不触碰任何存量业务表的数据。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl  = 'http://localhost:8080',
    [string]$MySqlCli = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database = 'rad_oa',
    # 用于 3.5 的低权限账号（只挂流程权限、不挂 ctms 权限）
    [string]$LimitedUser = 'zhangwei',
    # 引用保护夹具用的部门（必须是 sys_dept 里真实存在的部门：t_ctms_contract.dept_id 有 FK）
    [string]$DeptId = 'A070B84D000F4DC48F591AE081F3047E'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)   # 管道喂 SQL 给 mysql.exe 时必须（PS5.1 默认 ASCII 会把中文变 ?）

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
<# 含中文/引号的 SQL 走 stdin：PS 5.1 把中文拼进命令行会被转码坏（DEV-ENV §8.7） #>
function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('ctmsmd-' + [guid]::NewGuid().ToString('N') + '.sql')
    [System.IO.File]::WriteAllText($tmp, $sql, (New-Object System.Text.UTF8Encoding($false)))
    $out = (Get-Content $tmp -Raw -Encoding UTF8) | & $MySqlCli "--host=127.0.0.1" '--user=root' `
        '-D' $Database '--default-character-set=utf8mb4' 2>&1
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    return ($out -join "`n")
}
<# 只取标量：mysql 报错不吞掉 —— 返回以 ERROR 开头的串，让断言自己失败得显眼 #>
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

<# 后端业务异常时 HTTP 仍回 200，一律按响应体的 code 判定（DEV-ENV §9.4） #>
function Api([string]$method, [string]$url, $body, [string]$user = 'superAdmin') {
    $headers = @{ Authorization = "Bearer $(TokenOf $user)" }
    try {
        if ($method -eq 'GET') {
            return Invoke-RestMethod "$BaseUrl$url" -Method Get -Headers $headers -TimeoutSec 60
        }
        if ($null -eq $body) {
            return Invoke-RestMethod "$BaseUrl$url" -Method $method -Headers $headers -TimeoutSec 60
        }
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
function RowCount($res) { return @(RowsOf $res).Count }

<#
  强制刷新某个账号的权限缓存。

  为什么必须有这一步（实测踩过）：
    RuoYi 的权限集合是**登录时算出来并随 LoginUser 一起缓存在 Redis** 里的
    （`UserDetailsServiceImpl.createLoginUser` → `permissionService.getMenuPermission`），
    所以"往 sys_role_menu 里插一行"**不会**立刻改变已登录 token 的权限。
    而 `GET /getInfo` 会重新算一遍，并在不一致时 `tokenService.refreshToken(loginUser)`
    写回缓存 —— 这是唯一不需要重新登录就能生效的官方途径。
    不调用它的话，本脚本里"授权后应该能看见"的断言会一律假失败（3.5 段第一版就是这样）。
#>
function Refresh-Permissions([string]$user) {
    $r = Api 'GET' '/getInfo' $null $user
    if (-not (IsOk $r)) { throw "刷新 $user 的权限缓存失败：code=$($r.code) msg=$(MsgOf $r)" }
    return $r
}

# ================================================================ 夹具标识
# ⚠ 判据一律用 ASCII 编码/名称（DEV-ENV §6.25）：中文只用于"提示文案"断言，不用作存在性判据。
$S = (Get-Date -Format 'HHmmss') + (Get-Random -Minimum 100 -Maximum 999)
$CustCode   = "C$S"
$CustCode2  = "C2$S"
$SupCode    = "S$S"
$CustName   = "验收客户A-$S"
$CustNameEd = "验收客户A改-$S"
$CustName2  = "验收客户B-$S"
$SupName    = "验收供应商-$S"
$UomCode    = "U$S"
$WhCode     = "W$S"
$ProdCode   = "P$S"
$ContractId = "CMDCONTRACT$S"
$ContractNo = "CMDC-$S"
$TypeName   = "验收类型L"          # 类型按 name + 序号匹配；id 由服务端生成

# 运行时取回的、由服务端生成的主键
$CustId = ''; $CustId2 = ''; $SupId = ''; $UomId = ''; $WhId = ''; $ProdId = ''
$TypeIds = @()

# 夹具清理（幂等；收尾与"跑前预清理"共用）。
# ⚠ 按 code/name 前缀清，而不是"按前端传入的 id"：主键是服务端生成的 UUID。
function Clear-Fixtures {
    $sql = @"
DELETE FROM t_ctms_contract WHERE id='$ContractId';
DELETE FROM t_ctms_product  WHERE code LIKE 'P%$S' OR code LIKE 'P2%$S';
DELETE FROM t_ctms_uom      WHERE code LIKE 'U%$S';
DELETE FROM t_ctms_warehouse WHERE code LIKE 'W%$S';
DELETE FROM t_ctms_product_type WHERE name LIKE '验收类型L%$S' OR code LIKE 'PT%DUP%$S' OR code LIKE 'PT$S';
DELETE FROM t_ctms_customer WHERE code LIKE 'C%$S';
DELETE FROM t_ctms_supplier WHERE code LIKE 'S%$S';
"@
    $null = SqlFile $sql
}

# ------------------------------------------------------------------ 前置
Step '前置检查'
if (-not (Test-Path $MySqlCli)) { throw "缺少 MySQL 客户端：$MySqlCli" }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw '后端 8080 未监听 —— 先跑 .\start-env.ps1'
}
$null = TokenOf 'superAdmin'
$null = TokenOf $LimitedUser
$tables = @('t_ctms_customer','t_ctms_supplier','t_ctms_product_type','t_ctms_uom','t_ctms_warehouse','t_ctms_product','t_ctms_contract')
$missing = @()
foreach ($t in $tables) {
    if ((Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='$t'") -ne '1') { $missing += $t }
}
if ($missing.Count -gt 0) { throw "缺少 B3 业务表：$($missing -join ', ')（先执行 sql\二开-合同台账.sql）" }
$adminId = Sql "SELECT user_id FROM sys_user WHERE user_name='superAdmin' LIMIT 1"
Assert-That ($adminId -ne '') "superAdmin 的 user_id 取到（$adminId）"
Assert-That ((Sql "SELECT COUNT(*) FROM sys_dept WHERE dept_id='$DeptId'") -eq '1') "引用保护夹具用的部门存在（dept_id=$DeptId）"
Ok "后端在线；7 张 t_ctms_* 业务表齐备；夹具序号 $S"

Clear-Fixtures   # 预清理：上一轮异常退出留下的脏行不能影响本轮（DEV-ENV §6.25）
$roleId = Sql "SELECT r.role_id FROM sys_role r WHERE r.role_key='common' LIMIT 1"
$menuPartner = '9F2C0000000000000000000000000013'   # 菜单：往来单位
Info "3.5 用例使用的低权限账号=$LimitedUser（role_key=common, role_id=$roleId）"

try {
    # ============================================================ 3.1 客户档案
    Step '3.1 客户档案：新增（简称留空必须成功）'
    $r = Api 'POST' '/ctms/partner/customer' @{
        code = $CustCode; name = $CustName; shortName = ''
        taxNo = '91330000TEST0001X'; contactName = '联系人A'; contactPhone = '13800000001'
        address = '杭州市测试路 1 号'; bankName = '测试银行'; bankAccount = '6222000000000001'
        creditLimit = 12345.67; level = 'A'; enableFlag = '1'
    }
    Assert-That (IsOk $r) "新增客户成功（code=$($r.code) msg=$(MsgOf $r)）"
    $CustId = Sql "SELECT id FROM t_ctms_customer WHERE code='$CustCode'"
    Assert-That ($CustId -ne '' -and $CustId -notmatch '^ERROR') "客户行已落库（服务端生成 id=$CustId）"
    Assert-That ((Sql "SELECT IFNULL(short_name,'') FROM t_ctms_customer WHERE code='$CustCode'") -eq '') `
        '简称留空可保存（规格「简称可为空」：落库为空串或 NULL 都算通过，不报错）'
    Assert-That ((Sql "SELECT create_id FROM t_ctms_customer WHERE code='$CustCode'") -eq $adminId) `
        'create_id 由服务端快照成登录账号（请求体里没传 create_id）'

    Step '3.1 客户档案：编码 / 名称唯一'
    $r = Api 'POST' '/ctms/partner/customer' @{ code = $CustCode; name = "验收客户A2-$S" }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编码已存在') "重复编码被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/partner/customer' @{ code = "${CustCode}X"; name = $CustName }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '名称已存在') "重复名称被拒（msg=$(MsgOf $r)）"
    $dupCount = Sql "SELECT COUNT(*) FROM t_ctms_customer WHERE code='$CustCode'"
    Assert-That ($dupCount -eq '1') '被拒的两次请求没有留下任何行（编码仍只有 1 行）'
    Assert-That ((Sql "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_ctms_customer' AND index_name='uk_customer_code' AND non_unique=0") -ge '1') `
        'uk_customer_code 是**数据库侧唯一索引**（不是只靠应用层判重）'
    Assert-That ((Sql "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_ctms_customer' AND index_name='uk_customer_name' AND non_unique=0") -ge '1') `
        'uk_customer_name 是数据库侧唯一索引'

    Step '3.1/3.4 客户档案：编辑、查询、停用'
    $r = Api 'PUT' '/ctms/partner/customer' @{ id = $CustId; code = $CustCode; name = $CustNameEd; shortName = '验收简称' }
    Assert-That (IsOk $r) "编辑客户成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT short_name FROM t_ctms_customer WHERE id='$CustId'") -eq '验收简称') '简称从「留空」改为有值后正确落库（可空列可回填）'
    Assert-That ((Sql "SELECT name FROM t_ctms_customer WHERE id='$CustId'") -eq $CustNameEd) '名称改名已落库'
    $r = Api 'GET' "/ctms/partner/customer/$CustId"
    Assert-That ((IsOk $r) -and ($r.data.id -eq $CustId)) '按标识查详情返回同一 id（前端传入的 id 被忽略，服务端生成的才是真键）'

    $r = Api 'PUT' '/ctms/partner/customer/status' @{ id = $CustId; enableFlag = '0' }
    Assert-That (IsOk $r) "停用客户成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT enable_flag FROM t_ctms_customer WHERE id='$CustId'") -eq '0') '停用标志已落库'
    $r = Api 'PUT' '/ctms/partner/customer/status' @{ id = $CustId; enableFlag = '9' }
    Assert-That ((IsOk $r) -eq $false) "非法启用标志被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT enable_flag FROM t_ctms_customer WHERE id='$CustId'") -eq '0') '非法请求没有改动数据'

    Step '3.4 停用后不出现在「选择器」（/options），但管理列表仍可见'
    $r = Api 'GET' '/ctms/partner/customer/options'
    $inOptions = @($r.data | Where-Object { $_.id -eq $CustId }).Count
    Assert-That ((IsOk $r) -and ($inOptions -eq 0)) '停用客户**不在** /options（新增合同选择器的数据源）里'
    $r = Api 'GET' '/ctms/partner/customer/list?pageNum=1&pageSize=200'
    $inList = @(RowsOf $r | Where-Object { $_.id -eq $CustId }).Count
    Assert-That ((IsOk $r) -and ($inList -eq 1)) '停用客户**仍在**管理列表里（档案页要能看到并恢复）'
    $r = Api 'GET' '/ctms/partner/customer/list?pageNum=1&pageSize=200&enableFlag=1'
    $inEnabled = @(RowsOf $r | Where-Object { $_.id -eq $CustId }).Count
    Assert-That ($inEnabled -eq 0) '显式 enableFlag=1 时停用客户被过滤掉（列表筛选口径生效）'
    # 恢复启用，供后面 3.5 用
    $null = Api 'PUT' '/ctms/partner/customer/status' @{ id = $CustId; enableFlag = '1' }

    # ============================================================ 3.2 供应商
    Step '3.2 供应商档案：简称必填（新增路径）'
    $r = Api 'POST' '/ctms/partner/supplier' @{ code = $SupCode; name = $SupName; shortName = '' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '简称') "简称留空被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_supplier WHERE code='$SupCode'") -eq '0') '被拒的请求没有落库'

    Step '3.2 供应商档案：账期天数边界'
    $r = Api 'POST' '/ctms/partner/supplier' @{ code = $SupCode; name = $SupName; shortName = '验收供'; paymentDays = -1 }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '账期') "账期 -1 被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_supplier WHERE code='$SupCode'") -eq '0') '账期非法的请求没有落库'

    $r = Api 'POST' '/ctms/partner/supplier' @{
        code = $SupCode; name = $SupName; shortName = '验收供'
        supplyScope = '阀门/法兰'; paymentDays = 0; contactName = '供方联系人'; enableFlag = '1'
    }
    Assert-That (IsOk $r) "账期 0 可保存（msg=$(MsgOf $r)）"
    $SupId = Sql "SELECT id FROM t_ctms_supplier WHERE code='$SupCode'"
    Assert-That ((Sql "SELECT payment_days FROM t_ctms_supplier WHERE id='$SupId'") -eq '0') '账期 0 落库为 0（不是被当成空）'
    Assert-That ((Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_ctms_supplier' AND column_name='short_name' AND is_nullable='NO'") -eq '1') `
        'short_name 在数据库侧是 NOT NULL（「简称必填」不只靠应用层）'

    Step '3.2 供应商档案：修改路径同样校验简称'
    $r = Api 'PUT' '/ctms/partner/supplier' @{ id = $SupId; code = $SupCode; name = $SupName; shortName = '   ' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '简称') "修改提交空白简称被拒（msg=$(MsgOf $r)；空白视同空）"
    Assert-That ((Sql "SELECT short_name FROM t_ctms_supplier WHERE id='$SupId'") -eq '验收供') '被拒的修改没有覆盖原值'
    $r = Api 'PUT' '/ctms/partner/supplier' @{ id = $SupId; code = $SupCode; name = $SupName; shortName = '验收供改'; paymentDays = 30 }
    Assert-That (IsOk $r) "合法修改成功（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT payment_days FROM t_ctms_supplier WHERE id='$SupId'") -eq '30') '账期 30 落库'
    Assert-That ((Sql "SELECT short_name FROM t_ctms_supplier WHERE id='$SupId'") -eq '验收供改') '简称修改已落库'
    $r = Api 'POST' '/ctms/partner/supplier' @{ code = "${SupCode}X"; name = $SupName; shortName = 'X' }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '名称已存在') "供应商名称重复被拒（msg=$(MsgOf $r)）"

    # ============================================================ 3.4 引用保护
    Step '3.4 引用保护：有合同引用时禁止删除档案'
    # 引用是**真实外键 + 真实行**：直接插一份引用该客户的合同行（第 4 组的接口尚未交付，故用 SQL 造引用）
    $out = SqlFile "INSERT INTO t_ctms_contract (id,contract_no,name,type,party_a,party_b,subject_matter,amount,currency,dept_id,create_id,customer_id,status,arrival_status)
VALUES ('$ContractId','$ContractNo','引用保护夹具','销售','$CustNameEd','','',0.00,'CNY','$DeptId','$adminId','$CustId','内部审批中','未到货');"
    if ($out -match 'ERROR') { Bad "夹具合同写入报错：$out" }
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_contract WHERE id='$ContractId'") -eq '1') '已造一份引用该客户的合同行（真实外键引用）'
    $r = Api 'DELETE' "/ctms/partner/customer/$CustId" $null
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '引用') "有引用时删除被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_customer WHERE id='$CustId'") -eq '1') '被拒的删除没有真的删掉档案'
    # 供应商侧镜像：换 supplier_id 引用同一份合同
    $null = SqlFile "UPDATE t_ctms_contract SET supplier_id='$SupId', customer_id=NULL WHERE id='$ContractId';"
    $r = Api 'DELETE' "/ctms/partner/supplier/$SupId" $null
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '引用') "供应商侧同样：有引用时删除被拒（msg=$(MsgOf $r)）"
    $null = SqlFile "UPDATE t_ctms_contract SET supplier_id=NULL WHERE id='$ContractId';"

    Step '3.4 引用保护：零引用可以删除'
    $r = Api 'POST' '/ctms/partner/customer' @{ code = $CustCode2; name = $CustName2; enableFlag = '1' }
    Assert-That (IsOk $r) '再造一个零引用客户成功'
    $CustId2 = Sql "SELECT id FROM t_ctms_customer WHERE code='$CustCode2'"
    $r = Api 'DELETE' "/ctms/partner/customer/$CustId2" $null
    Assert-That (IsOk $r) "零引用客户可删除（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_customer WHERE id='$CustId2'") -eq '0') '档案行确实被删除（规格：仅零引用放行）'

    # ============================================================ 3.5 数据范围
    Step '3.5 往来单位档案不做数据范围隔离'
    Assert-That ((Sql "SELECT create_id FROM t_ctms_customer WHERE id='$CustId'") -eq $adminId) `
        '夹具客户由 superAdmin 创建（用来验证"他人创建的档案可见"）'
    # 给 common 角色临时挂上「往来单位」菜单，用完即摘（夹具只碰 sys_role_menu）
    # ⚠ 每次改完 sys_role_menu 都必须 Refresh-Permissions，否则 token 里缓存的还是旧权限集合
    $null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$roleId' AND menu_id='$menuPartner';"
    $null = Refresh-Permissions $LimitedUser
    $r = Api 'GET' '/ctms/partner/customer/list?pageNum=1&pageSize=200' $null $LimitedUser
    Assert-That (IsForbidden $r) "未授权账号访问档案列表 403（code=$($r.code) msg=$(MsgOf $r)）"
    Assert-That ((RowCount $r) -eq 0) '403 时返回体里没有任何档案行（不是"接口 200 但被前端藏起来"）'

    $null = SqlFile "INSERT INTO sys_role_menu (role_id, menu_id) VALUES ('$roleId','$menuPartner');"
    $null = Refresh-Permissions $LimitedUser
    $r = Api 'GET' '/ctms/partner/customer/list?pageNum=1&pageSize=200' $null $LimitedUser
    $seen = @(RowsOf $r | Where-Object { $_.id -eq $CustId }).Count
    Assert-That ((IsOk $r) -and ($seen -eq 1)) `
        "$LimitedUser（非创建人）能看到 superAdmin 创建的客户档案（不做数据范围隔离）"
    $r = Api 'GET' '/ctms/partner/supplier/list?pageNum=1&pageSize=200' $null $LimitedUser
    $seenSup = @(RowsOf $r | Where-Object { $_.id -eq $SupId }).Count
    Assert-That ((IsOk $r) -and ($seenSup -eq 1)) "$LimitedUser 也能看到他人创建的供应商档案"
    # 列表可见**不等于**能改：再确认一次"只给了 list，没给 remove"时删除仍 403
    $r = Api 'DELETE' "/ctms/partner/customer/$CustId" $null $LimitedUser
    Assert-That (IsForbidden $r) "只挂 list 权限时删除仍 403（权限点粒度生效，msg=$(MsgOf $r)）"
    $null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$roleId' AND menu_id='$menuPartner';"
    $null = Refresh-Permissions $LimitedUser

    # ============================================================ 3.3 物料域
    Step '3.3 物料域：类型树 5 级上限'
    $parentId = ''
    $levels = @()
    $created = @()
    $okChain = $true
    for ($lv = 1; $lv -le 5; $lv++) {
        $body = @{ name = "$TypeName$lv-$S"; sort = $lv; enableFlag = '1' }
        if ($null -ne $parentId -and $parentId -ne '') { $body.parentId = $parentId } else { $body.code = "PT$S" }
        $r = Api 'POST' '/ctms/product-type' $body
        if (-not (IsOk $r)) { $okChain = $false; Bad "第 $lv 级创建失败：$(MsgOf $r)"; break }
        $tid = Sql "SELECT id FROM t_ctms_product_type WHERE name='$TypeName$lv-$S'"
        $created += $tid
        $levels += (Sql "SELECT CONCAT(level,':',path) FROM t_ctms_product_type WHERE id='$tid'")
        $parentId = $tid
    }
    Assert-That ($okChain -and $created.Count -eq 5) '能建到第 5 级（1→5 全部成功）'
    Assert-That ($levels.Count -eq 5 -and $levels[0] -match '^1:/') "第 1 级的 level/path 正确（$($levels[0])）"
    Assert-That ($levels.Count -eq 5 -and $levels[4] -match '^5:/') "第 5 级的 level/path 正确（$($levels[4])）"
    Assert-That ($levels.Count -eq 5 -and (($levels[4] -split ':')[1].Split('/').Count -eq 6)) `
        '第 5 级物化路径是 5 段（/a/b/c/d/e/），证明 path 是逐级拼接而不是写死'
    $r = Api 'POST' '/ctms/product-type' @{ name = "$TypeName6-$S"; parentId = $parentId }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '5 级') "第 6 级被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_product_type WHERE name='$TypeName6-$S'") -eq '0') '被拒的第 6 级没有落库'

    Step '3.3 物料域：同父同名唯一 / 有子类型禁删'
    # ⚠ PowerShell 插值坑：`"$TypeName1-$S"` 里 `$TypeName1` 是**一个未定义变量**（不是 $TypeName 加 1），
    #   于是这个名字与已建的第 1 级根本不同、查重自然查不到 —— 表现为"重复名居然能建"的假失败。
    #   变量名后紧跟数字/字母/`?`/`:`/`%` 时一律用 `${}` 显式界定（与 DEV-ENV §6.22 同源）。
    $dupName = "$TypeName" + "1-$S"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_product_type WHERE name='$dupName' AND parent_id IS NULL") -eq '1') `
        "第 1 级同名类型确实已存在（名字=$dupName），作为下面「重复必须被拒」的对照"
    $r = Api 'POST' '/ctms/product-type' @{ name = $dupName; code = "PTDUP$S" }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '同名') "同父（根）下同名类型被拒（msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_product_type WHERE name='$dupName'") -eq '1') '被拒的重复名没有落库'
    # 不同父下同名允许：在第 5 级下挂一个与第 1 级同名的子类型
    # 不同父下同名允许：在第 3 级下再挂一个与第 1 级同名的类型。
    #   · 父不同（第 1 级挂在第 2 级下；这一份挂在第 3 级下）→ 允许，这正是"唯一性只约束同父"的证据；
    #   · ⚠ 别挂在第 1 级下：那是根，与第 1 级同父（parent_id is null）→ 会被正确地判成重复；
    #   · ⚠ 也别挂在第 5 级下：会变成第 6 级，先被层级守卫拦下（错误信息不同，断言会误判）。
    $r = Api 'POST' '/ctms/product-type' @{ name = $dupName; code = "PTDUP2$S"; parentId = $created[2] }
    Assert-That (IsOk $r) "不同父下同名类型允许（唯一性只约束同父，msg=$(MsgOf $r)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_product_type WHERE name='$dupName'") -eq '2') '不同父的同名行确实落库（共 2 行）'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_ctms_product_type WHERE name='$dupName' AND parent_id='$($created[2])'") -eq '1') '新增行挂在与第 1 级不同的父下'
    $null = SqlFile "DELETE FROM t_ctms_product_type WHERE code='PTDUP2$S';"
    $r = Api 'DELETE' "/ctms/product-type/$($created[0])" $null
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '下级') "有子类型时删除被拒（msg=$(MsgOf $r)）"

    Step '3.3 物料域：非叶子类型不能挂物料'
    $r = Api 'POST' '/ctms/product' @{ name = "验收物料-$S"; productTypeId = $created[0]; uomId = 'X'; defaultPrice = 1 }
    Assert-That ((IsOk $r) -eq $false) "非叶子类型挂物料被拒（msg=$(MsgOf $r)）"

    Step '3.3 物料域：计量单位小数位 0~4'
    $r = Api 'POST' '/ctms/uom' @{ code = $UomCode; name = "验收单位-$S"; decimals = 5 }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '小数位') "小数位 5 被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/uom' @{ code = $UomCode; name = "验收单位-$S"; decimals = -1 }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '小数位') "小数位 -1 被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/uom' @{ code = $UomCode; name = "验收单位-$S"; decimals = 4 }
    Assert-That (IsOk $r) "小数位 4 可保存（边界内）（msg=$(MsgOf $r)）"
    $UomId = Sql "SELECT id FROM t_ctms_uom WHERE code='$UomCode'"
    $r = Api 'POST' '/ctms/uom' @{ code = $UomCode; name = "验收单位重复-$S"; decimals = 2 }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编码已存在') "单位编码重复被拒（msg=$(MsgOf $r)）"

    Step '3.3 物料域：仓库编码唯一 / 物料落库'
    $r = Api 'POST' '/ctms/warehouse' @{ code = $WhCode; name = "验收仓库-$S"; enableFlag = '1' }
    Assert-That (IsOk $r) "仓库新增成功（msg=$(MsgOf $r)）"
    $WhId = Sql "SELECT id FROM t_ctms_warehouse WHERE code='$WhCode'"
    $r = Api 'POST' '/ctms/warehouse' @{ code = $WhCode; name = "验收仓库重复-$S" }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编码已存在') "仓库编码重复被拒（msg=$(MsgOf $r)）"

    $r = Api 'POST' '/ctms/product' @{
        code = $ProdCode; name = "验收球阀-$S"; spec = 'DN50'
        productTypeId = $created[4]; uomId = $UomId; defaultPrice = 12.3456; safetyStock = 5; enableFlag = '1'
    }
    Assert-That (IsOk $r) "叶子类型下新增物料成功（msg=$(MsgOf $r)）"
    $ProdId = Sql "SELECT id FROM t_ctms_product WHERE code='$ProdCode'"
    Assert-That ($ProdId -ne '' -and $ProdId -notmatch '^ERROR') "物料行已落库（id=$ProdId）"
    $r = Api 'POST' '/ctms/product' @{ code = $ProdCode; name = "验收球阀重复-$S"; productTypeId = $created[4]; uomId = $UomId }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '编码已存在') "物料编码重复被拒（msg=$(MsgOf $r)）"
    $r = Api 'POST' '/ctms/product' @{ code = "P2$S"; name = "验收负价物料-$S"; productTypeId = $created[4]; uomId = $UomId; defaultPrice = -0.01 }
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '单价') "默认单价为负被拒（msg=$(MsgOf $r)）"

    Step '3.3 物料域：被引用的单位 / 有物料的类型禁止删除'
    $r = Api 'DELETE' "/ctms/uom/$UomId" $null
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '引用') "被物料引用的单位禁止删除（msg=$(MsgOf $r)）"
    $r = Api 'DELETE' "/ctms/product-type/$($created[4])" $null
    Assert-That ((IsOk $r) -eq $false -and (MsgOf $r) -match '物料') "挂有物料的类型禁止删除（msg=$(MsgOf $r)）"
    $r = Api 'DELETE' "/ctms/product/$ProdId" $null
    Assert-That (IsOk $r) "物料本身可以删除（msg=$(MsgOf $r)）"
    $r = Api 'DELETE' "/ctms/uom/$UomId" $null
    Assert-That (IsOk $r) "引用解除后单位可删除（msg=$(MsgOf $r)）"

    Step '3.3 物料域：类型与仓库查询接口可用'
    $r = Api 'GET' '/ctms/product-type/list?pageNum=1&pageSize=200' $null
    Assert-That ((IsOk $r) -and ([int]$r.total -ge 5)) "类型列表可用（total=$($r.total)）"
    $r = Api 'GET' '/ctms/product-type/tree' $null
    Assert-That (IsOk $r) "类型树接口可用（返回 $(@($r.data).Count) 个根）"
    $r = Api 'GET' '/ctms/warehouse/list?pageNum=1&pageSize=200' $null
    Assert-That ((IsOk $r) -and (@(RowsOf $r | Where-Object { $_.id -eq $WhId }).Count -eq 1)) '仓库列表包含本轮新增的仓库'
}
finally {
    Step '收尾：删除全部夹具并还原角色菜单'
    Clear-Fixtures
    # 再删一次：即使 3.5 段中途抛异常，也要保证"没有给 common 角色偷偷加权限"
    $null = SqlFile "DELETE FROM sys_role_menu WHERE role_id='$roleId' AND menu_id='$menuPartner';"
    $left = Sql "SELECT (SELECT COUNT(*) FROM t_ctms_customer WHERE code LIKE 'C%$S') + (SELECT COUNT(*) FROM t_ctms_supplier WHERE code LIKE 'S%$S') + (SELECT COUNT(*) FROM t_ctms_product_type WHERE name LIKE '验收类型L%$S' OR code LIKE 'PT$S' OR code LIKE 'PT%DUP%$S') + (SELECT COUNT(*) FROM t_ctms_uom WHERE code LIKE 'U%$S') + (SELECT COUNT(*) FROM t_ctms_warehouse WHERE code LIKE 'W%$S') + (SELECT COUNT(*) FROM t_ctms_product WHERE code LIKE 'P%$S' OR code LIKE 'P2%$S') + (SELECT COUNT(*) FROM t_ctms_contract WHERE id='$ContractId')"
    Info "夹具残留行数（应为 0）：$left"
    Info "common 角色残留 ctms 授权行数（应为 0）：$(Sql "SELECT COUNT(*) FROM sys_role_menu WHERE role_id='$roleId' AND menu_id='$menuPartner'")"
}

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
