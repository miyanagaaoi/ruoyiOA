<#
================================================================================
 erp-smoke.ps1 —— B4 进销存「只读冒烟」（t20 用；**不建任何业务数据**）
--------------------------------------------------------------------------------
 目的：后端打包重启之后，用**一个脚本**把"前后端契约面"快速探一遍，输出一份
       可读的「端点 × HTTP code × body code × 关键字段」清单；404/405/参数名/权限点
       不一致会在这里被**低成本**暴露（E2E 之前唯一能做这一步的地方）。

 三条纪律：
   ① **只读**：只发 GET 与"默认不修复"的 `POST /stk/stock/recalc`（`repair` 缺省 false，
      只做校验、不改结存）；不做任何新增/编辑/审核/删除 ⇒ 不污染 E2E 的基线数据。
   ② **失败要看得见**：每条探针打印 `HTTP code` + 响应体里的 `code` + 关键字段；
      失败时**原样打印 body**（遵守 DEV-ENV §6.56：RuoYi 认证失败是 HTTP 200 + body code=401，
      只看 `Invoke-RestMethod` 抛不抛异常会漏判）。
   ③ **不做断言调优**：`FAIL` 就是 FAIL，不为了"看起来绿"放宽判据；`未打包/404` 属预期结果，
      照样逐条列出来给 t20 消费。

 用法（仓库根目录；先 `start-env.ps1` + `oa-login.ps1`）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-smoke.ps1
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\erp-smoke.ps1 -Strict   # 有 FAIL 时退出码 1

 退出码：0 = 跑完（默认，即使有 FAIL）；1 = 跑完且 `-Strict` 下有 FAIL；2 = 前置不满足（无 token / 后端未在线）

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl  = 'http://localhost:8080',
    [string]$CacheDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$User     = 'superAdmin',
    [switch]$Strict
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$script:Total = 0
$script:Ok = 0
$script:Fail = 0
$script:Skip = 0
$script:FailList = @()

function Step ($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Info ($m) { Write-Host "    $m" -ForegroundColor DarkGray }

function TokenOf([string]$user) {
    $f = Join-Path $CacheDir "token-$user.txt"
    if (-not (Test-Path $f)) { throw "缺少 token 文件：$f（先跑 .\tools\oa-login.ps1）" }
    return (Get-Content $f -Raw).Trim()
}

<# 一次探测：返回 http 状态码 + 原始 body 文本（transport 错误时 http=0） #>
function Invoke-Probe([string]$method, [string]$url, $body = $null) {
    $headers = @{ Authorization = "Bearer $(TokenOf $User)" }
    try {
        if ($method -eq 'GET') {
            $resp = Invoke-WebRequest -Uri "$BaseUrl$url" -Method Get -Headers $headers `
                                      -UseBasicParsing -TimeoutSec 60
        }
        elseif ($null -eq $body) {
            $resp = Invoke-WebRequest -Uri "$BaseUrl$url" -Method $method -Headers $headers `
                                      -UseBasicParsing -TimeoutSec 60
        }
        else {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20 -Compress))
            $resp = Invoke-WebRequest -Uri "$BaseUrl$url" -Method $method -Headers $headers `
                                      -ContentType 'application/json; charset=utf-8' `
                                      -Body $bytes -UseBasicParsing -TimeoutSec 60
        }
        return @{ http = [int]$resp.StatusCode; text = [string]$resp.Content; transport = $false }
    }
    catch {
        $r = $_.Exception.Response
        if ($null -ne $r) {
            $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
            $text = $reader.ReadToEnd()
            return @{ http = [int]$r.StatusCode; text = $text; transport = $false }
        }
        return @{ http = 0; text = $_.Exception.Message; transport = $true }
    }
}

<# body 可能不是 JSON（404 页、空体）；统一容错 #>
function BodyJson($text) {
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    try { return ($text | ConvertFrom-Json) } catch { return $null }
}

<# 关键字段摘要：按 json data/data.rows/total 等常见形状取；取不到就返回 '-' #>
function KeyFields($j) {
    if ($null -eq $j) { return 'body=非 JSON' }
    $parts = @()
    if ($null -ne $j.code) { $parts += "code=$($j.code)" }
    if ($null -ne $j.total) { $parts += "total=$($j.total)" }
    if ($null -ne $j.rows) { $parts += "rows=$(@($j.rows).Count)" }
    if ($null -ne $j.data) {
        if ($j.data -is [array]) { $parts += "data=$(@($j.data).Count) 项" }
        elseif ($j.data -is [bool]) { $parts += "data=$($j.data)" }
        elseif ($j.data -is [string]) { $parts += "data=" + $(if ($j.data.Length -gt 24) { $j.data.Substring(0, 24) + '…' } else { $j.data }) }
        else {
            $names = @($j.data.PSObject.Properties.Name)
            $parts += "data{$($names.Count) 字段}"
            foreach ($k in @('status', 'posted', 'inconsistentCount', 'docNo')) {
                if ($names -contains $k) { $parts += "$k=$($j.data.$k)" }
            }
        }
    }
    if ($null -ne $j.msg -and "$($j.msg)" -ne '') {
        $m = [string]$j.msg
        $parts += "msg=" + $(if ($m.Length -gt 60) { $m.Substring(0, 60) + '…' } else { $m })
    }
    if ($parts.Count -eq 0) { return 'body 无 code/total/rows/data/msg' }
    return ($parts -join ' ')
}

$script:Probes = @()

<#
  登记一条探针。
    Check：给定 http/json，返回 $true/$false（**判据写在这里，不做兜底放行**）
    RouteProbe：路由探针 —— 期望"路由存在"：HTTP 200 且业务码非 200（业务层拒绝，
                因为传的是不存在的 id）；HTTP 404/405 说明路由或方法不存在 ⇒ FAIL。
#>
function Probe([string]$id, [string]$name, [string]$method, [string]$path, $body, [string]$expect, [scriptblock]$check) {
    $script:Probes += [pscustomobject]@{
        id = $id; name = $name; method = $method; path = $path; body = $body
        expect = $expect; check = $check
    }
}

# ---------------------------------------------------------------- 探针清单
# A. 8 类单据列表（分页只取 1 条，只读）
Probe 'S01' '采购申请单列表'   'GET' '/erp/pur/request/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S02' '采购单列表'       'GET' '/erp/pur/order/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S03' '销售申请单列表'   'GET' '/sal/request/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S04' '销售订单列表'     'GET' '/sal/order/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S05' '入库单列表'       'GET' '/stk/in-order/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S06' '出库单列表'       'GET' '/stk/out-order/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S07' '盘点单列表'       'GET' '/stk/take/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读（t9 新端点）' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S08' '调拨单列表'       'GET' '/stk/transfer/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读（t9 新端点）' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }

# B. 库存账（结存 / 流水 / 一致性校验）
Probe 'S09' '结存明细列表'     'GET' '/stk/stock/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S10' '结存明细带筛选'   'GET' '/stk/stock/list?pageNum=1&pageSize=1&belowSafetyOnly=true&beginQty=0' $null `
    'HTTP 200 + code=200（筛选参数名被接受，不报 400/500）' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) }
Probe 'S11' '结存明细详情（不存在的 key）' 'GET' '/stk/stock/detail?productId=ZZPROBEZZ&warehouseId=ZZPROBEZZ' $null `
    'HTTP 200 + 业务码非 200（detail 端点在线的判据；真实参数名是 productId+warehouseId，不是 id）' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -ne 200) }
Probe 'S12' '一致性校验 recalc（默认不修复）' 'POST' '/stk/stock/recalc' @{} `
    'HTTP 200 + code=200 + 含 inconsistentCount（**只读**：repair 缺省 false）' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.data) -and (@($j.data.PSObject.Properties.Name) -contains 'inconsistentCount') }
Probe 'S13' '库存流水列表'     'GET' '/stk/ledger/list?pageNum=1&pageSize=1' $null `
    'HTTP 200 + code=200 + total/rows 可读' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.total) }
Probe 'S14' '库存流水带筛选'   'GET' '/stk/ledger/list?pageNum=1&pageSize=1&productId=__probe__&warehouseId=__probe__' $null `
    'HTTP 200 + code=200（筛选参数名被接受）' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) }
Probe 'S15' '流水详情（不存在 id）' 'GET' '/stk/ledger/detail?id=ZZPROBEZZ' $null `
    'HTTP 200 + 业务码非 200（404/403，可区分）' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -ne 200) }

# C. 主数据 3 个选择器（前端下拉的数据源）
Probe 'S16' '物料选择器'   'GET' '/ctms/product/options'   $null `
    'HTTP 200 + code=200 + data 为数组' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.data) }
Probe 'S17' '仓库选择器'   'GET' '/ctms/warehouse/options' $null `
    'HTTP 200 + code=200 + data 为数组' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.data) }
Probe 'S18' '单位选择器'   'GET' '/ctms/uom/options'       $null `
    'HTTP 200 + code=200 + data 为数组' { param($h, $j) ($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200) -and ($null -ne $j.data) }

# D. 附件对象类型（应为 9 项注册：contract + 8 类单据）
Probe 'S19' '附件对象类型注册表' 'GET' '/ctms/attachment/object-types' $null `
    'HTTP 200 + code=200 + registered 恰好 9 项（contract + 8 类单据）' {
        param($h, $j)
        if (-not (($h -eq 200) -and ($null -ne $j) -and ($j.code -eq 200))) { return $false }
        $reg = $null
        if ($null -ne $j.registered) { $reg = @($j.registered) }               # 该控制器把 registered 放在**根**上（不是 data 里）
        elseif ($null -ne $j.data -and $null -ne $j.data.registered) { $reg = @($j.data.registered) }
        if ($null -eq $reg) { return $false }
        if ($reg.Count -ne 9) { return $false }
        foreach ($k in @('contract', 'purchase_request', 'purchase_order', 'sales_request', 'sales_order',
                         'stock_in', 'stock_out', 'stock_take', 'stock_transfer')) {
            if ($reg -notcontains $k) { return $false }
        }
        return $true
    }

# E. 8 类单据的动作路由形态（只读路由探针：传不存在的 id，期望"业务层拒绝"而不是 404/405）
# ⚠ id 必须**只含 [A-Za-z0-9]**：8 类单据的动作路由把 {id} 约束成了 `[A-Za-z0-9]+`，
#   用 `__probe__` 这类带下划线的 id 会**匹配不上路由**从而 404 —— 那是探针的假失败，不是契约问题。
$probeId = 'ZZPROBEZZ'
$routeProbes = @(
    @{ kind = 'purchase_request'; mode = 'put-id-action';  path = "/erp/pur/request/$probeId/submit" },
    @{ kind = 'purchase_order';   mode = 'put-id-action';  path = "/erp/pur/order/$probeId/submit" },
    @{ kind = 'sales_request';    mode = 'put-id-action';  path = "/sal/request/$probeId/submit" },
    @{ kind = 'sales_order';      mode = 'put-id-action';  path = "/sal/order/$probeId/submit" },
    @{ kind = 'stock_in';         mode = 'post-action-id'; path = "/stk/in-order/submit/$probeId" },
    @{ kind = 'stock_out';        mode = 'post-action-id'; path = "/stk/out-order/submit/$probeId" },
    @{ kind = 'stock_take';       mode = 'post-id-action'; path = "/stk/take/$probeId/submit" },
    @{ kind = 'stock_transfer';   mode = 'post-id-action'; path = "/stk/transfer/$probeId/submit" }
)
$routeNo = 20
foreach ($rp in $routeProbes) {
    $id = 'S{0:D2}' -f $routeNo
    $routeNo++
    $method = if ($rp.mode -eq 'put-id-action') { 'PUT' } else { 'POST' }
    Probe $id ("动作路由：" + $rp.kind + "（" + $rp.mode + "）") $method $rp.path $null `
        'HTTP 200 + 业务码非 200（路由存在；传不存在的 id 应由业务层拒绝）' {
            param($h, $j)
            ($h -eq 200) -and ($null -ne $j) -and ($j.code -ne 200)
        }
}

# ---------------------------------------------------------------- 执行
Step "只读冒烟：$BaseUrl（user=$User）"
Info "共 $($script:Probes.Count) 条探针；不建任何业务数据"

$firstTransport = $false
foreach ($p in $script:Probes) {
    $script:Total++
    $r = Invoke-Probe $p.method $p.path $p.body
    $j = BodyJson $r.text
    $key = KeyFields $j
    $pass = $false
    if ($r.transport) {
        $firstTransport = $true
    }
    else {
        try { $pass = [bool](& $p.check $r.http $j) } catch { $pass = $false }
    }
    $line = "[{0}] {1} {2} {3} → HTTP {4} / {5}" -f $p.id, $p.method, $p.path, $p.name, $r.http, $key
    if ($r.transport) {
        $script:Skip++
        Write-Host ("    [SKIP] " + $line) -ForegroundColor Yellow
        Info ("           传输层失败：" + $r.text)
    }
    elseif ($pass) {
        $script:Ok++
        Write-Host ("    [ OK ] " + $line) -ForegroundColor Green
    }
    else {
        $script:Fail++
        $script:FailList += ("{0} {1} {2} → HTTP {3}" -f $p.id, $p.method, $p.path, $r.http)
        Write-Host ("    [FAIL] " + $line) -ForegroundColor Red
        Info ("           期望：" + $p.expect)
        $raw = [string]$r.text
        if ($raw.Length -gt 600) { $raw = $raw.Substring(0, 600) + '…(截断)' }
        Info ("           原始 body：" + $raw)
    }
}

Write-Host ""
Step "冒烟结论"
Write-Host ("    探针 {0} 条：通过 {1} / 失败 {2} / 跳过 {3}" -f $script:Total, $script:Ok, $script:Fail, $script:Skip)
if ($firstTransport) {
    Write-Host "    ⚠ 存在传输层失败（HTTP 0）：后端可能未启动或端口不对 —— 先跑 .\start-env.ps1" -ForegroundColor Yellow
}
if ($script:Fail -gt 0) {
    Write-Host "    失败清单（交 t20 的「契约不一致清单」逐条派单）：" -ForegroundColor Red
    foreach ($f in $script:FailList) { Write-Host ("      - " + $f) -ForegroundColor Red }
    Write-Host "    注：**未打包/404 属预期结果**，不因此放宽判据；清单原样交回 captain。" -ForegroundColor DarkGray
}
else {
    Write-Host "    全部探针通过。" -ForegroundColor Green
}

if ($Strict -and $script:Fail -gt 0) { exit 1 }
if ($firstTransport -and $script:Ok -eq 0) { exit 2 }
exit 0
