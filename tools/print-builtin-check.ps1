<#
================================================================================
 print-builtin-check.ps1 —— 2.0 B2「多内置打印模板」回归（真实环境）
--------------------------------------------------------------------------------
 为什么单独一个脚本：AC-83 点名要求本批新增「多内置打印模板回归」。
 这条链路的失败形态全都是**静默**的 ——
   · 内置版式还是"全局唯一一套"（切换 key 后打印件没变）；
   · 版式 key 落库了但读不出来（五处同步漏一处 → 字段被静默丢弃）；
   · 回退内置时又被 fillDefaults 把签批栏填成"关闭"（资金审批单没有签名区，AC-62）；
   · 版式常量在前端又有了一份副本（地雷 D-4 复活，REQ-PRINT-013）。
 所以这里必须**打接口 + 查库 + 看真实单据的打印聚合**三头对。

 覆盖（对应 oa-print-builtin-templates/tasks.md）：
   1.1 增量脚本幂等：t_template.builtin_print_key 存在、默认 contract（脚本另跑两遍验证）
   1.2 五处同步的**真实往返**：保存非默认 key → 重新读回该值（不是只看 DDL）
   1.3 内置版式清单接口返回 4 条（key + 名称），contract 名称与升级前逐字一致
   1.4 4 套版式的栏目与 PRD 附录 A 逐项对应（逐条断言栏目文案）
   1.5 非法 key：读接口回退 contract、写接口**拒绝**且库值不变
   1.6 优先级链未变：显式 printTplId > 启用行 > 内置；内置分支按键取版式
   2.4 打印聚合响应不含死字段 formSchema，且 formData 非空
   3.1 内置 fund 版式照样出签批栏（showSignature='1'）—— AC-62 的服务端落点
   3.3 默认值语义一致：走接口写入与直插库两条路径读到的签批栏/附件清单取值相同
   4.5 抄送栏数据随聚合返回（ccNodes 是数组）
   6.3 「切换 key 后打印件随之变化」：同一张单据换 key → 标题与栏目都变

 用法（在仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\print-builtin-check.ps1

 前置：后端 8080 在线；.cache\token-superAdmin.txt 有效（失效先跑 .\tools\oa-login.ps1）；
       RabbitMQ 在线（/biz/form/save 走 MQ 异步落「我起草的」）。

 副作用（**严格隔离**，这是踩过 §9.6 之后的铁律）：
   · 夹具一律**克隆**模板（id 固定前缀 B2PRINT*），绝不改共享模板的任何字段；
   · 克隆件的 def_key 指向**已发布的存量流程**（testSerial），因此能发起真实单据、
     拿到真实流程节点，而不需要重新发布任何流程；
   · 收尾删除克隆模板、克隆打印模板与夹具单据（finally 里做，含失败路径）。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string]$BaseUrl          = 'http://localhost:8080',
    [string]$MySqlCli         = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir         = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database         = 'rad_oa',
    # 克隆源：一个"能发起、且已发布流程（defKey=testSerial）"的存量模板
    [string]$SourceTemplateId = '1ADB8299342C4D4FA59EC9F38AB5C768',
    [string]$CloneId          = 'B2PRINTTPL0000000000000000000A1',
    [string]$ClonePrintTplId  = 'B2PRINTPRT0000000000000000000A1'
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

<# 带引号/多语句的 SQL 走 stdin（PS 5.1 把含双引号的 SQL 拼进命令行会被截断） #>
function SqlFile([string]$sql) {
    $tmp = Join-Path $env:TEMP ('b2print-' + [guid]::NewGuid().ToString('N') + '.sql')
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

<# HTTP：业务码非 200 时后端仍回 HTTP 200，统一按响应体判断 #>
function Api([string]$method, [string]$url, $body, [string]$user = 'superAdmin') {
    $headers = @{ Authorization = "Bearer $(TokenOf $user)" }
    try {
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

<# 从 fieldMap（可能是 JSON 字符串）里按顺序取出所有字段表栏目文案 #>
function ColumnsOf($fieldMap) {
    if (-not $fieldMap) { return @() }
    $map = $fieldMap
    if ($map -is [string]) {
        try { $map = $map | ConvertFrom-Json } catch { return @() }
    }
    $out = New-Object System.Collections.ArrayList
    foreach ($sec in $map.sections) {
        if ($sec.rows) {
            foreach ($row in $sec.rows) {
                foreach ($c in $row.cells) {
                    if ($c.label) { $null = $out.Add([string]$c.label) }
                }
            }
        }
    }
    return $out.ToArray()
}

<# 签批栏的固定栏目（sign.columns） #>
function SignColumnsOf($fieldMap) {
    if (-not $fieldMap) { return @() }
    $map = $fieldMap
    if ($map -is [string]) {
        try { $map = $map | ConvertFrom-Json } catch { return @() }
    }
    $out = New-Object System.Collections.ArrayList
    foreach ($sec in $map.sections) {
        if ($sec.id -eq 'sign' -and $sec.columns) {
            foreach ($c in $sec.columns) {
                if ($c.label) { $null = $out.Add([string]$c.label) }
            }
        }
    }
    return $out.ToArray()
}

function Join-List($arr) { return (($arr | ForEach-Object { [string]$_ }) -join ' | ') }

# ------------------------------------------------------------------ 前置
Step '前置检查'
if (-not (Test-Path $MySqlCli)) { throw "缺少文件：$MySqlCli" }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw '后端 8080 未监听 —— 先跑 .\start-env.ps1'
}
$null = TokenOf 'superAdmin'
Assert-That ((Sql "SELECT COUNT(*) FROM t_template WHERE id='$SourceTemplateId'") -eq '1') `
    "克隆源模板存在（$SourceTemplateId）"
Assert-That ((Sql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_template' AND COLUMN_NAME='builtin_print_key' AND COLUMN_DEFAULT='contract'") -eq '1') `
    't_template.builtin_print_key 存在且默认 contract（增量脚本 1.1）'

$bizId = ''
$cloneReady = $false
# 桥接验收单据的"临时改动"痕迹（异常路径也要还原，所以变量在 try 之前初始化）
$bridgeTpl = 'BRIDGEAC17TPL0000000000000000A1'
$bridgeRow = ''
$bridgeKeyBefore = ''
try {
    # ============================================================== 夹具：克隆模板（含 def_key=testSerial，复用已发布流程）
    Step '夹具：克隆一个可发起的模板（不改共享模板的任何字段）'
    # ⚠ 先做一次"预清理"：上一次异常退出可能留下指向该克隆 id 的打印模板行，
    #   而一条**启用**的打印模板会顶掉内置版式，让后面所有"回退内置"的断言假失败（踩过）。
    #   清理条件一律用 **ASCII** 的 template_id/id，不用中文名（中文名匹配在别处可靠，
    #   但作收尾条件就多一个失败面 —— 判定"清干净了没"必须只看 id）。
    $null = SqlFile @"
DELETE FROM t_template_print_template WHERE template_id='$CloneId' OR id='$ClonePrintTplId';
DELETE FROM t_template_submit_scope WHERE template_id='$CloneId';
DELETE FROM t_template_flow_admin WHERE template_id='$CloneId';
DELETE FROM t_template WHERE id='$CloneId';
"@
    $cloneSql = @"
INSERT INTO t_template (id, name, type, def_key, form_id, form_key, form_type, form_code,
                        main_text_flag, attach_flag, message_notice_flag, enable_flag, del_flag, sort,
                        builtin_print_key, create_id, create_by, create_time, update_time)
SELECT '$CloneId', 'B2打印版式回归', type, def_key, form_id, form_key, form_type, form_code,
       main_text_flag, attach_flag, message_notice_flag, '1', '0', sort,
       'contract', create_id, create_by, NOW(), NOW()
  FROM t_template WHERE id='$SourceTemplateId';
"@
    $out = SqlFile $cloneSql
    if ($out -match 'ERROR') { Info "夹具写入输出：$out" }
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template WHERE id='$CloneId'") -eq '1') '夹具模板已就位'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template_print_template WHERE template_id='$CloneId' AND del_flag='0' AND enable_flag='1'") -eq '0') `
        '夹具模板下没有任何启用的自定义打印模板（否则后面验证的不是内置版式）'
    Assert-That ((Sql "SELECT def_key FROM t_template WHERE id='$CloneId'") -eq 'testSerial') `
        '夹具模板复用已发布流程标识 testSerial（因此不需要重新发布流程）'
    $cloneReady = $true

    # ============================================================== 1.2 真实往返（五处同步）
    Step '1.2 五处同步的真实往返：写非默认 key → 重新读回'
    $setFund = Api 'POST' '/workflow/print/builtinKey' @{ templateId = $CloneId; builtinKey = 'fund' } 'superAdmin'
    Assert-That ($setFund.code -eq 200) "写入 builtin_print_key=fund（code=$($setFund.code) msg=$(MsgOf $setFund)）"
    Assert-That ((Sql "SELECT builtin_print_key FROM t_template WHERE id='$CloneId'") -eq 'fund') `
        '查库确认列值已更新（不是只回了 200）'
    $tplList = Api 'GET' "/template/template/list?pageNum=1&pageSize=500" $null
    $cloneRow = $tplList.rows | Where-Object { $_.id -eq $CloneId } | Select-Object -First 1
    Assert-That ([bool]$cloneRow -and $cloneRow.builtinPrintKey -eq 'fund') `
        "列表接口回读该列 = $($cloneRow.builtinPrintKey)（漏任一处会表现为字段被静默丢弃）"

    # ============================================================== 1.3 内置版式清单
    Step '1.3 内置版式清单接口：恰好 4 条，contract 名称与升级前逐字一致'
    $list = Api 'GET' '/workflow/print/builtinTemplates' $null
    Assert-That ($list.code -eq 200) "接口返回（code=$($list.code)）"
    $keys = @($list.data | ForEach-Object { $_.key })
    Assert-That ($keys.Count -eq 4) "返回恰好 4 条（实际 $($keys.Count)：$(Join-List $keys)）"
    Assert-That ((Join-List $keys) -eq 'contract | fund | matter | payment') "key 集合与顺序为 contract/fund/matter/payment"
    $contractName = ($list.data | Where-Object { $_.key -eq 'contract' } | Select-Object -First 1).name
    Assert-That ($contractName -eq '集团合同类文件流转审批单') "contract 名称 = $contractName（升级前原值，零回归锚点）"
    $fundOpt = $list.data | Where-Object { $_.key -eq 'fund' } | Select-Object -First 1
    Assert-That ($fundOpt.showSignature -eq '1') 'fund 版式自带"签批栏开启"的推荐取值（AC-62 的前提）'
    Assert-That ($fundOpt.showAttachment -eq '0') 'fund 版式自带附件清单推荐取值（维持"显式打开"的既有定位）'

    Step '2.1 权限点复用：无权限账号请求内置版式清单返回 403（未新增权限点）'
    $deniedNames = New-Object System.Collections.ArrayList
    $deniedMsg = ''
    foreach ($u in @('zhangwei', 'lina', 'wangqiang', 'zhaomin')) {
        if (-not (Test-Path (Join-Path $CacheDir "token-$u.txt"))) { continue }
        $r = Api 'GET' '/workflow/print/builtinTemplates' $null $u
        # ⚠ 后端在业务异常时 HTTP 仍回 200，一律看响应体的 code（DEV-ENV §9.4）
        if ($r.code -eq 403) { $null = $deniedNames.Add($u); $deniedMsg = [string]$r.msg }
    }
    Assert-That ($deniedNames.Count -ge 1) "无 workflow:print:template 权限的账号被拒（403）：$(Join-List $deniedNames)"
    Assert-That ($deniedMsg -like '*没有权限*') "拒绝原因明确（msg=$deniedMsg）"

    # ============================================================== 1.4 四套版式栏目
    Step '1.4 四套版式的栏目与 PRD 附录 A 逐项对应'
    $expect = @{
        contract = @('提报单位', '报送人', '报送时间', '合同编号', '合同全称', '合同签订主体-甲方',
                     '合同签订主体-乙方', '合同金额', '履约开始', '履约结束', '合同签订时间',
                     '其他会审部门', '相关说明')
        fund     = @('审批单位', '事项分类', '计划类别', '付款归属期', '资金审批内容')
        matter   = @('公文来源-报送单位', '报送人', '报送时间', '文件编号', '信息名称', '文件类型',
                     '加急程度', '文件概要')
        payment  = @('日期', '计划内/计划外', '收款单位名称', '开户银行', '账号', '付款单位名称',
                     '开户行', '一级分类', '二级分类', '付款归属期', '结算方式', '回单',
                     '银行付款用途', '付款事由', '金额', '金额大写', '备注')
    }
    $maps = @{}
    foreach ($k in @('contract', 'fund', 'matter', 'payment')) {
        $r = Api 'GET' "/workflow/print/defaultFieldMap/$k" $null
        Assert-That ($r.code -eq 200) "GET /defaultFieldMap/$k 返回（code=$($r.code)）"
        $maps[$k] = $r.data
        $cols = ColumnsOf $r.data
        $want = $expect[$k]
        Assert-That ((Join-List $cols) -eq (Join-List $want)) "$k 栏目逐项对应（实际：$(Join-List $cols)）"
    }
    Assert-That ((Join-List (SignColumnsOf $maps['fund'])) -eq '集团职能部门 | 集团分管领导 | 集团董事长') `
        'fund 的签批栏是固定三栏（集团职能部门/集团分管领导/集团董事长）'
    Assert-That ((SignColumnsOf $maps['contract']).Count -eq 0) 'contract 的签批栏仍是"按流程节点动态出栏"（零回归）'
    Assert-That ((Join-List (SignColumnsOf $maps['payment'])) -eq '经理 | 财务 | 部门负责人 | 财务负责人 | 制单人 | 领款人') `
        'payment 的签批栏六栏齐备'

    # ============================================================== 1.5 非法 key
    Step '1.5 非法版式键：读接口回退 contract，写接口拒绝且库值不变'
    $badRead = Api 'GET' '/workflow/print/defaultFieldMap/__not_a_key__' $null
    Assert-That ($badRead.code -eq 200 -and (Join-List (ColumnsOf $badRead.data)) -eq (Join-List $expect['contract'])) `
        '非法 key 的读接口回退 contract（不抛异常，版式降级优于打不出纸）'
    $keyBefore = Sql "SELECT builtin_print_key FROM t_template WHERE id='$CloneId'"
    $badWrite = Api 'POST' '/workflow/print/builtinKey' @{ templateId = $CloneId; builtinKey = 'fund-v2' } 'superAdmin'
    Assert-That ($badWrite.code -ne 200) "非法 key 被拒绝（code=$($badWrite.code) msg=$(MsgOf $badWrite)）"
    Assert-That ((Sql "SELECT builtin_print_key FROM t_template WHERE id='$CloneId'") -eq $keyBefore) `
        "拒绝时库值保持原值（仍为 $keyBefore）"
    $badEmpty = Api 'POST' '/workflow/print/builtinKey' @{ templateId = $CloneId; builtinKey = '' } 'superAdmin'
    Assert-That ($badEmpty.code -ne 200) '空 key 同样被拒绝（不静默纠正成 contract）'

    # ============================================================== 单据夹具：发起一张单据（草稿）
    Step '夹具：在该克隆模板上发起一张单据（草稿）'
    $formCfg = '{"formData":{"fields":[]},"valData":{"amount":100,"reason":"B2打印版式回归"}}'
    $saveRes = Api 'POST' '/biz/form/save' @{
        templateId = $CloneId
        formData   = @{ formData = $formCfg }
    } 'superAdmin'
    Assert-That ($saveRes.code -eq 200) "保存成功（code=$($saveRes.code) msg=$(MsgOf $saveRes)）"
    $bizId = [string]$saveRes.data
    Info "businessId=$bizId"
    # ⚠ 模板关联是**异步**落到 t_workflow_my_draft 的（走 RabbitMQ），所以轮询等；
    #   而且 t_workflow_form.template_id 对动态表单**恒为 NULL**（实测），
    #   所以"草稿能不能解析出自己的单据模板"完全依赖 my_draft 这一路（见下面两条断言）。
    $draftRow = 0
    for ($i = 0; $i -lt 15; $i++) {
        $draftRow = [int](Sql "SELECT COUNT(*) FROM t_workflow_my_draft WHERE biz_id='$bizId' AND template_id='$CloneId'")
        if ($draftRow -ge 1) { break }
        Start-Sleep -Seconds 1
    }
    Assert-That ($draftRow -ge 1) '「我起草的」已异步落库该单据与克隆模板的关联'
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_form WHERE id='$bizId' AND template_id IS NULL") -eq '1') `
        't_workflow_form 的 template_id 对动态表单为空（这正是必须读 my_draft 的原因）'
    $pdDraft = Api 'GET' "/workflow/print/data/$bizId" $null
    Assert-That ($pdDraft.code -eq 200) "草稿的打印聚合可读（code=$($pdDraft.code)）"
    Assert-That ($pdDraft.data.templateId -eq $CloneId) `
        "草稿解析到自己的单据模板（templateId=$($pdDraft.data.templateId)）—— 只靠 t_workflow_form 会解析成 null，进而打成默认版式"
    Assert-That ($null -ne $pdDraft.data.formData) '草稿的打印聚合带回表单数据（字段表不为空）'

    # ============================================================== 6.3 切换 key → 打印件随之变化
    Step '6.3 同一张单据：切换内置版式 key，打印聚合的标题与栏目都随之变化'
    $expectTitle = @{
        contract = '集团合同类文件流转审批单'
        fund     = '集团资金审批单'
        matter   = '集团事项类打印审批单'
        payment  = '付款申请单'
    }
    $seenTitles = New-Object System.Collections.ArrayList
    foreach ($k in @('contract', 'fund', 'matter', 'payment')) {
        $w = Api 'POST' '/workflow/print/builtinKey' @{ templateId = $CloneId; builtinKey = $k } 'superAdmin'
        Assert-That ($w.code -eq 200) "切到 $k（code=$($w.code)）"
        $pd = Api 'GET' "/workflow/print/data/$bizId" $null
        Assert-That ($pd.code -eq 200) "打印聚合可读（$k，code=$($pd.code) msg=$(MsgOf $pd)）"
        $d = $pd.data
        $null = $seenTitles.Add([string]$d.title)
        Assert-That ($d.templateId -eq $CloneId) "$k 的聚合解析到克隆模板（$($d.templateId)）"
        Assert-That ($d.title -eq $expectTitle[$k]) "$k 的打印件标题 = $($d.title)"
        Assert-That ([string]$d.printTemplate.builtinKey -eq $k) "$k 的生效模板带回 builtinKey=$($d.printTemplate.builtinKey)"
        $gotCols = ColumnsOf $d.printTemplate.fieldMap
        Assert-That ((Join-List $gotCols) -eq (Join-List $expect[$k])) "$k 的打印件栏目与版式常量一致（$(Join-List $gotCols)）"
        # 3.1 / AC-62：内置版式照样出签批栏
        Assert-That ([string]$d.printTemplate.showSignature -eq '1') "$k 的签批栏为开启（内置模板不再被强制关闭 —— AC-62）"
        Assert-That (-not $d.printTemplate.id) "$k 走的是内置版式（printTemplate.id 为空）"
        # 2.4 死字段
        Assert-That ($null -eq $d.PSObject.Properties['formSchema']) "$k 的聚合响应不含死字段 formSchema"
        # 4.5 抄送栏数据
        Assert-That ($null -ne $d.ccNodes) "$k 的聚合响应带回 ccNodes 数组"
    }
    $distinct = ($seenTitles | Sort-Object -Unique).Count
    Assert-That ($distinct -eq 4) "四套版式给出 4 个不同标题（切换 key 后打印件确实随之变化）：$(Join-List $seenTitles)"

    # ============================================================== 3.1 真实流程节点的签批栏（AC-62）
    # 草稿没有流程节点，所以"三栏签名区按节点出栏"必须拿一张**真的在流程里跑过**的单据来验。
    # 做法：**临时**把桥接验收那张单据的启用自定义模板停用、并把它的模板切到 fund，
    #       让内置版式接管，断言签批栏有真实节点；随后**逐项还原**（finally 里再兜一次）。
    Step '3.1 真实流程节点 + 内置 fund 版式：签批栏有节点可渲染（AC-62）'
    $bridgeBiz = '0BDA4366218844918A02906EB0D7C733'
    $bridgeRow = Sql "SELECT id FROM t_template_print_template WHERE template_id='$bridgeTpl' AND del_flag='0' AND enable_flag='1' LIMIT 1"
    $bridgeKeyBefore = Sql "SELECT builtin_print_key FROM t_template WHERE id='$bridgeTpl'"
    if (-not $bridgeRow) {
        Info '桥接验收单据没有启用的自定义打印模板，跳过本段（不影响其余断言）'
    } else {
        $null = SqlFile "UPDATE t_template_print_template SET enable_flag='0' WHERE id='$bridgeRow';"
        $null = SqlFile "UPDATE t_template SET builtin_print_key='fund' WHERE id='$bridgeTpl';"
        $pdBridge = Api 'GET' "/workflow/print/data/$bridgeBiz" $null
        Assert-That ($pdBridge.code -eq 200) "桥接验收单据的打印聚合可读（code=$($pdBridge.code)）"
        Assert-That ($pdBridge.data.templateId -eq $bridgeTpl) "解析到桥接验收的单据模板（$($pdBridge.data.templateId)）"
        Assert-That ([string]$pdBridge.data.printTemplate.builtinKey -eq 'fund') `
            "停用自定义模板后由内置 fund 版式接管（builtinKey=$($pdBridge.data.printTemplate.builtinKey)）"
        Assert-That ($pdBridge.data.title -eq '集团资金审批单') "打印件标题 = $($pdBridge.data.title)"
        Assert-That ($pdBridge.data.nodes.Count -ge 1) `
            "打印聚合带回真实流程节点 $($pdBridge.data.nodes.Count) 条（三栏签名区有内容可渲染）"
        Assert-That ([string]$pdBridge.data.printTemplate.showSignature -eq '1') '内置 fund 版式的签批栏为开启（AC-62 的服务端落点）'
        $fundCols = SignColumnsOf (Api 'GET' '/workflow/print/defaultFieldMap/fund' $null).data
        Assert-That ((Join-List $fundCols) -eq '集团职能部门 | 集团分管领导 | 集团董事长') `
            "fund 的三栏签名区来自版式常量（$(Join-List $fundCols)）"
        # 还原
        $null = SqlFile "UPDATE t_template_print_template SET enable_flag='1' WHERE id='$bridgeRow';"
        $null = SqlFile "UPDATE t_template SET builtin_print_key='$bridgeKeyBefore' WHERE id='$bridgeTpl';"
        Assert-That ((Sql "SELECT enable_flag FROM t_template_print_template WHERE id='$bridgeRow'") -eq '1') `
            '桥接验收的启用自定义模板已还原'
        Assert-That ((Sql "SELECT builtin_print_key FROM t_template WHERE id='$bridgeTpl'") -eq $bridgeKeyBefore) `
            "桥接验收模板的 builtin_print_key 已还原为 $bridgeKeyBefore"
    }
    Assert-That ([bool]$pdDraft.data.submitterCompany) `
        "发起人所属公司已解析（$($pdDraft.data.submitterCompany)）—— 资金/事项版式「审批单位/报送单位」的数据来源"

    Step '2.4 打印件字段表仍取单据自身的表单快照（formData 非空）'
    Assert-That ($null -ne $pdDraft.data.formData) '打印聚合的 formData 非空（删掉 formSchema 不影响取数路径）'

    # ============================================================== 3.3 默认值语义一致
    Step '3.3 默认值语义一致：接口写入与直插库两条路径读到的取值相同'
    $ddl = Sql "SELECT CONCAT(IFNULL((SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_template_print_template' AND COLUMN_NAME='show_signature'),'?'),'/',IFNULL((SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_template_print_template' AND COLUMN_NAME='show_attachment'),'?'))"
    Assert-That ($ddl -eq '0/0') "DDL 默认值已对齐服务端口径（show_signature/show_attachment = $ddl）"
    # ① 走接口写入（不给签批栏/附件清单取值）
    $apiTpl = Api 'POST' '/workflow/print/template' @{
        templateId = $CloneId; name = 'B2默认值-接口写入'; paper = 'A4'; orientation = 'portrait'
    } 'superAdmin'
    Assert-That ($apiTpl.code -eq 200) "接口写入一条打印模板（code=$($apiTpl.code) msg=$(MsgOf $apiTpl)）"
    $apiVals = Sql "SELECT CONCAT(show_signature,'/',show_attachment) FROM t_template_print_template WHERE name='B2默认值-接口写入' AND del_flag='0' LIMIT 1"
    # ② 直插库（不给这两列）
    $null = SqlFile @"
DELETE FROM t_template_print_template WHERE id='$ClonePrintTplId';
INSERT INTO t_template_print_template (id, template_id, name, paper, orientation, enable_flag, del_flag, create_time, update_time)
VALUES ('$ClonePrintTplId', '$CloneId', 'B2默认值-直插库', 'A4', 'portrait', '0', '0', NOW(), NOW());
"@
    $sqlVals = Sql "SELECT CONCAT(show_signature,'/',show_attachment) FROM t_template_print_template WHERE id='$ClonePrintTplId'"
    Assert-That ($apiVals -eq $sqlVals) "两条写入路径取值相同（接口=$apiVals，直插库=$sqlVals）"
    Assert-That ($apiVals -eq '0/0') "两条路径的取值都是「空值→关闭」的服务端口径（$apiVals）"

    # ============================================================== 1.6 优先级链
    Step '1.6 优先级链未变：显式 printTplId > 启用行 > 内置版式'
    # ③ 内置分支：先把该克隆模板下的启用行全部停用，才能走到"回退内置"这一段
    $null = SqlFile "UPDATE t_template SET builtin_print_key='fund' WHERE id='$CloneId';"
    $null = SqlFile "UPDATE t_template_print_template SET enable_flag='0' WHERE template_id='$CloneId';"
    $effBuiltin = Api 'GET' "/workflow/print/template/$CloneId" $null
    Assert-That ([string]$effBuiltin.data.builtinKey -eq 'fund') "无启用行 → 回退内置，按键取版式（builtinKey=$($effBuiltin.data.builtinKey)）"
    Assert-That ($effBuiltin.data.title -eq '集团资金审批单') "内置标题按 key 取（$($effBuiltin.data.title)）"
    # ② 启用行
    $null = SqlFile "UPDATE t_template_print_template SET enable_flag='1' WHERE id='$ClonePrintTplId';"
    $effEnabled = Api 'GET' "/workflow/print/template/$CloneId" $null
    Assert-That ($effEnabled.data.id -eq $ClonePrintTplId) "有启用行 → 用启用行（id=$($effEnabled.data.id)）"
    Assert-That ([string]::IsNullOrEmpty([string]$effEnabled.data.builtinKey)) '启用行不是内置模板（builtinKey 为空）'
    # ① 显式 printTplId
    # ⚠ URL 里的变量必须写成 ${var}：`"$CloneId?printTplId=..."` 会被 PowerShell 当成
    #    变量名 `CloneId?printTplId`（整段被替换成空串），请求静默发到错误的 URL（踩过）。
    $effExplicit = Api 'GET' "/workflow/print/template/${CloneId}?printTplId=${ClonePrintTplId}" $null
    Assert-That ($effExplicit.data.id -eq $ClonePrintTplId) "显式 printTplId 优先（id=$($effExplicit.data.id)）"
}
finally {
    Step '收尾：还原临时改动 + 删除全部夹具（含失败路径）'
    # ① 还原桥接验收单据的临时改动（本脚本唯一的"动到共享数据"之处，必须无条件还原）
    if ($bridgeRow) {
        $null = SqlFile "UPDATE t_template_print_template SET enable_flag='1' WHERE id='$bridgeRow';"
        $left = Sql "SELECT enable_flag FROM t_template_print_template WHERE id='$bridgeRow'"
        if ($left -eq '1') { Info "桥接验收的自定义模板已还原为启用（$bridgeRow）" }
        else { Bad "桥接验收的自定义模板未还原（enable_flag=$left）—— 请手工执行 UPDATE t_template_print_template SET enable_flag='1' WHERE id='$bridgeRow';" }
    }
    if ($bridgeKeyBefore) {
        $null = SqlFile "UPDATE t_template SET builtin_print_key='$bridgeKeyBefore' WHERE id='$bridgeTpl';"
        $leftKey = Sql "SELECT builtin_print_key FROM t_template WHERE id='$bridgeTpl'"
        if ($leftKey -eq $bridgeKeyBefore) { Info "桥接验收模板的 builtin_print_key 已还原为 $bridgeKeyBefore" }
        else { Bad "桥接验收模板的 builtin_print_key 未还原（=$leftKey）—— 请手工改回 $bridgeKeyBefore" }
    }
    # ② 删除夹具（条件一律用 ASCII 的 template_id / id：判定"清干净了没"只看 id）
    $null = SqlFile @"
DELETE FROM t_template_print_template WHERE template_id='$CloneId' OR id='$ClonePrintTplId';
DELETE FROM t_template_submit_scope WHERE template_id='$CloneId';
DELETE FROM t_template_flow_admin WHERE template_id='$CloneId';
DELETE FROM t_template WHERE id='$CloneId';
"@
    if ($bizId) {
        $null = SqlFile @"
DELETE FROM t_workflow_todo WHERE business_id='$bizId';
DELETE FROM t_workflow_done WHERE business_id='$bizId';
DELETE FROM t_workflow_recycle WHERE business_id='$bizId';
DELETE FROM t_workflow_my_draft WHERE biz_id='$bizId';
DELETE FROM t_workflow_form WHERE id='$bizId';
"@
    }
    $left = Sql "SELECT COUNT(*) FROM t_template WHERE id='$CloneId'"
    $leftPrt = Sql "SELECT COUNT(*) FROM t_template_print_template WHERE template_id='$CloneId'"
    if ($left -ne '0' -or $leftPrt -ne '0') {
        Bad "夹具未清理干净（模板 $left 行 / 打印模板 $leftPrt 行）—— 请手工删除 template_id='$CloneId' 的相关行"
    } else { Info '夹具模板 / 打印模板 / 夹具单据已清理（共享模板未改动）' }
    if ($cloneReady) {
        Info "若中途异常退出，请确认共享模板未被改动：SELECT builtin_print_key FROM t_template WHERE id='$SourceTemplateId'"
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
