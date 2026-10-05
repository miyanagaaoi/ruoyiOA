<#
================================================================================
 flow-regression.ps1 —— 简化流程「组合矩阵」回归基线（跑在**真实环境**上）
--------------------------------------------------------------------------------
 为什么需要它：
   ruoyi-workflow/src/test 下的冒烟测试用的是
   ProcessEngineConfiguration.createStandaloneProcessEngineConfiguration()
   + 独立库 flow_smoke —— 即「独立引擎 + jar 自建 schema」。
   而生产是「Spring 托管引擎 + RuoYi 定制 + rad_oa（schema 来自 SQL 脚本）」。

   两者 schema 版本并不一致（实测 rad_oa 没有 ACT_RU_INCIDENT 表、
   ACT_RU_JOB 列名也不同），因此：
     - ${nrOfCompletedInstances == nrOfInstances} 在 flow_smoke 算得对，
       在 rad_oa 里恒为假 → 并行会审「全部完成」不合流（流程卡死）
     - flow_smoke 里手工塞的 {nodeId}_approval 变量生产环境根本没人创建
       → 会签节点报 Variable 'xxx_approval' was not found

   这两个缺陷冒烟测试全都没抓到 —— **所以必须有跑在真实环境上的回归**。

 覆盖维度：
   条件分支 / 并行会审(ALL) / 并行会审(ANY) / 首位会签+或签 / 条件+并行+必经

 断言（每个用例都必须满足）：
   1) 关键节点按预期出现（如条件分支只走一条、并行生成 N 个任务）
   2) 多实例节点的任务 ASSIGNEE_ 非空（否则没人能在「我的待办」里看到）
   3) **流程实例最终办结**（ACT_HI_PROCINST.END_TIME_ 非空、历史里出现 end）
      —— 只断言「任务清空」是不够的：卡死的实例同样满足任务清空

 用法：
   powershell -ExecutionPolicy Bypass -File flow-regression.ps1
   powershell -ExecutionPolicy Bypass -File flow-regression.ps1 -Only testParallel

 前置：MySQL(3306) / Redis(6379) / RabbitMQ(5672) / 后端(8080) 已启动，
       且 .cache\token-<user>.txt 里是有效 token（见 doc\测试数据-流程功能验证.md §7）。

 @author 二开
================================================================================
#>
# ⚠ 2026-10-05 加固（t15：并行会审 ALL 合流"卡死"根因修复）：
#   本脚本原先对 /workflow/handle/startFlow 与 /biz/flow/submit 的返回**只判字段不判 code**
#   （多处写成 `$null = Post ...`），而 RuoYi 的"认证失败"是 **HTTP 200 + body {"code":401}**
#   （AuthenticationEntryPointImpl 不回 401 状态码），Invoke-RestMethod 既不抛异常、脚本也看不见。
#   后果：某个审批人凭据过期 → 他的提交被静默拒绝 → 多实例只剩部分活动任务 → 实例不办结
#   → 脚本报成"多实例 ALL 合流未触发、流程卡死"（HANDOFF §3.2 的现场就是这么来的，不是引擎缺陷）。
#   加固内容（**只增加断言，不删用例、不放宽任何既有断言**）：
#     1) 跑前校验 5 个账号的 token（/system/user/profile），失效直接中止并提示先跑 tools\oa-login.ps1；
#     2) 每次 startFlow / submit 断言 code == 200，不等即红并打印原始 body；
#     3) 用例失败时打印现场：实例 id / 活动任务数 / 活动执行数 / ACT_RE_PROCDEF 版本。
#   通用坑已登记 DEV-ENV §6.56。
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MySqlCli = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$CacheDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$Database = 'rad_oa',
    [string[]]$Only = @(),
    [int]$PollSeconds = 60
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)   # 管道喂 SQL 给 mysql.exe 时必须（PS5.1 默认 ASCII 会把中文列名变 ?）

$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

# ------------------------------------------------------------------ 基础工具

function Say  ($m) { Write-Host $m }
function Step ($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok   ($m) { Write-Host "    [OK] $m" -ForegroundColor Green }
function Bad  ($m) { Write-Host "    [!!] $m" -ForegroundColor Red }

function Assert-That([bool]$cond, [string]$desc) {
    if ($cond) { Ok $desc; $script:Pass++ } else { Bad $desc; $script:Fail++; $script:Failures += $desc }
}

function Sql([string]$q) {
    $out = & $MySqlCli "--host=127.0.0.1" '--user=root' "-D" $Database `
                       '--batch' '--skip-column-names' "--default-character-set=utf8mb4" -e $q 2>&1
    return (($out | Where-Object { $_ -notmatch '^mysql: \[Warning\]' }) | Select-Object -First 1)
}

function TokenOf([string]$user) {
    $f = Join-Path $CacheDir "token-$user.txt"
    if (-not (Test-Path $f)) { throw "缺少 token 文件：$f（先登录该账号，见文档 §7）" }
    return (Get-Content $f -Raw).Trim()
}

function Post([string]$url, $body, [string]$user) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 20 -Compress))
    return Invoke-RestMethod "$BaseUrl$url" -Method Post `
        -Headers @{ Authorization = "Bearer $(TokenOf $user)" } `
        -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 60
}

# ------------------------------------------------------------------ 提交/发起的 code 断言

# 记录现场（用例失败时打印用）
$script:LastProcInsId = $null
$script:LastProcDefId = $null

# 2026-10-05 加固：POST 必须判 code。
#   RuoYi 认证失败 = HTTP 200 + body code=401（AuthenticationEntryPointImpl），
#   Invoke-RestMethod 不抛异常 ⇒ 只看"有没有字段"的写法会把"审批被 401 拒掉"当成"审批成功"。
#   注意：这里**只加断言**，不改变任何请求体。
function Post-Ok([string]$url, $body, [string]$user, [string]$what) {
    $r = Post $url $body $user
    $code = $null
    if ($null -ne $r) { $code = $r.code }
    if ($code -ne 200) {
        $raw = ''
        try { $raw = ($r | ConvertTo-Json -Depth 20 -Compress) } catch { $raw = "$r" }
        $msg = ''
        if ($null -ne $r) { $msg = $r.msg }
        throw "$what（账号 $user）被拒：code=$code msg=$msg；原始 body=$raw"
    }
    if ($url -like '*startFlow*' -and $r.data) {
        $script:LastProcInsId = $r.data.procInsId
        $script:LastProcDefId = $r.data.defId
        if (-not $script:LastProcDefId) { $script:LastProcDefId = $r.data.procDefId }
    }
    return $r
}

# 跑前凭据校验：token 失效会让审批被静默拒绝，必须在跑用例之前就红。
function Assert-Tokens() {
    $bad = @()
    foreach ($u in @('superAdmin', 'zhangwei', 'lina', 'wangqiang', 'zhaomin')) {
        try {
            $r = Invoke-RestMethod "$BaseUrl/system/user/profile" `
                -Headers @{ Authorization = "Bearer $(TokenOf $u)" } -TimeoutSec 15
            if ($r.code -ne 200) { $bad += ("{0}(code={1})" -f $u, $r.code) }
        } catch {
            $bad += ("{0}({1})" -f $u, $_.Exception.Message)
        }
    }
    if ($bad.Count -gt 0) {
        $hint = 'RuoYi 的认证失败是 HTTP 200 + body code=401，不判 code 会把「审批被拒」误报成「流程卡死」（HANDOFF §3.2 就是这么来的，见 DEV-ENV §6.56）。先跑：powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\oa-login.ps1'
        throw ('账号凭据无效：' + ($bad -join '; ') + '。' + $hint)
    }
}

# 失败现场：实例 id / 活动任务数 / 活动执行数 / 流程定义版本（task 要求逐次留档的就是这四项）
function Show-Diag() {
    $pi = $script:LastProcInsId
    if (-not $pi) { return }
    $pd = $script:LastProcDefId
    Say ("    [diag] 实例 procInsId={0}  procDefId={1}" -f $pi, $pd)
    Say ("    [diag] ACT_HI_PROCINST.END_TIME_ = {0}" -f (Sql "SELECT IFNULL(END_TIME_,'(未办结)') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='$pi'"))
    Say ("    [diag] 活动任务数 = {0}（ACT_RU_TASK）" -f (ActiveCount $pi))
    foreach ($t in (ActiveTasks $pi)) { Say ("    [diag]    任务 {0}" -f $t) }
    $act = Sql "SELECT IFNULL(SUM(IS_ACTIVE_),0) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='$pi'"
    $all = Sql "SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='$pi'"
    Say ("    [diag] ACT_RU_EXECUTION 活动/全部 = {0}/{1}" -f $act, $all)
    Say ("    [diag] ACT_RE_PROCDEF = {0}" -f (Sql "SELECT CONCAT(KEY_,' v',VERSION_,' deployment=',DEPLOYMENT_ID_) FROM ACT_RE_PROCDEF WHERE ID_='$pd'"))
}

# assignee(user_id) → 登录名（用于挑对 token 去提交任务）
$script:OwnerByUserId = @{
    '58FE8668233B422FB69EE575F5F402A5' = 'zhangwei'
    '192F606172CB4405AFD3FA1976CE4098' = 'lina'
    '85122F49DC8A4626A1B870FAD25C8CF8' = 'wangqiang'
    '29D5380BCE6A41AD935D4275DCF40DEF' = 'zhaomin'
    'superAdmin'                       = 'superAdmin'
}
function OwnerOf([string]$assignee) {
    if ($script:OwnerByUserId.ContainsKey($assignee)) { return $script:OwnerByUserId[$assignee] }
    return 'superAdmin'
}

# 模板/流程的 id 都从库里取，避免硬编码
function TemplateId([string]$defKey) { return (Sql "SELECT id FROM t_template WHERE def_key='$defKey'") }

function ActiveTasks([string]$pi) {
    $raw = & $MySqlCli "--host=127.0.0.1" '--user=root' "-D" $Database '--batch' '--skip-column-names' `
                       "--default-character-set=utf8mb4" `
                       -e "SELECT CONCAT(ID_,'|',IFNULL(ASSIGNEE_,''),'|',TASK_DEF_KEY_) FROM ACT_RU_TASK WHERE PROC_INST_ID_='$pi' ORDER BY ID_;" 2>&1
    return @($raw | Where-Object { $_ -match '\|' })
}
function ActiveCount([string]$pi)  { return [int](Sql "SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_='$pi'") }
function Ended([string]$pi)        { return [bool](Sql "SELECT IFNULL(END_TIME_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='$pi'") }

# 等待异步消费把流程推进到位（完成是经 RabbitMQ 异步处理的，不能查完立刻断言）
function Wait-Ended([string]$pi, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (Ended $pi) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# ------------------------------------------------------------------ 用例执行

# 发起流程并一路「谁的任务谁签」直到办结，返回诊断信息
function Run-Flow {
    param(
        [string]$DefKey,
        [hashtable]$Variables = @{},
        [string]$Starter = 'superAdmin',
        [int]$MaxSteps = 30
    )
    $tpl = TemplateId $DefKey
    if (-not $tpl) { throw "库里找不到 $DefKey 的模板（t_template）" }

    $start = Post-Ok '/workflow/handle/startFlow' @{ templateId = $tpl; variables = $Variables } $Starter '发起流程'
    $pi = $start.data.procInsId
    $defId = $start.data.procDefId
    Say "    实例 procInsId=$pi  procDefId=$defId"

    $seen = @{}
    for ($i = 1; $i -le $MaxSteps; $i++) {
        if (Ended $pi) { break }
        $rows = ActiveTasks $pi
        if ($rows.Count -eq 0) { Start-Sleep -Seconds 2; continue }
        $row = $rows | Select-Object -First 1
        $p = $row -split '\|'
        $tid = $p[0]; $assignee = $p[1]; $key = $p[2]
        $seen[$key] = 1 + ($seen[$key] | ForEach-Object { $_ })   # 记录访问过的节点
        $owner = OwnerOf $assignee
        $null = Post-Ok '/biz/flow/submit' @{
            operateType = '200'
            flowTask = @{
                taskId = $tid; procInsId = $pi; taskDefKey = $key; defId = $defId
                comment = "回归测试($owner)"; templateId = $tpl
                templateType = (Sql "SELECT type FROM t_template WHERE id='$tpl'")
                type = 'TODO'; handleType = 'AUDIT'; variables = @{}
            }
        } $owner "提交任务($owner/$key)"
        Start-Sleep -Seconds 3     # 给异步消费留时间
    }

    return [pscustomobject]@{
        ProcInsId = $pi
        Ended     = (Ended $pi)
        Remaining = (ActiveCount $pi)
        Nodes     = $seen
        History   = @(& $MySqlCli "--host=127.0.0.1" '--user=root' "-D" $Database '--batch' '--skip-column-names' `
                                  "--default-character-set=utf8mb4" `
                                  -e "SELECT ACT_ID_ FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='$pi' ORDER BY START_TIME_;" 2>&1)
    }
}

function Assert-Ended($r, [string]$name) {
    Assert-That ($r.Remaining -eq 0) "$name · 无剩余活动任务"
    Assert-That ($r.History -contains 'end') "$name · 历史中出现 end 事件"
    Assert-That ($r.Ended) "$name · **流程实例已办结**（END_TIME_ 非空）"
}

# ------------------------------------------------------------------ 用例定义

$cases = [ordered]@{

    # 条件分支：两条互斥条件里只能命中一条（分支顺序即优先级），且必须走排他网关。
    # 2026-10-05 起 testCondition 的条件字段随「关联表单」改为合同类审批单，
    # 两条条件分别是 field101 = 经营 / field101 = 经济（见 doc/缺陷-流程条件字段与关联表单错位.md）。
    'testCondition' = {
        $r = Run-Flow -DefKey 'testCondition' -Variables @{ field101 = '经营' }
        Assert-That ($r.Nodes.ContainsKey('n2')) 'testCondition · 命中「经营」分支 → 经发部审批'
        Assert-That (-not $r.Nodes.ContainsKey('n3')) 'testCondition · 未命中「经济」分支 → 未走财务部'
        Assert-Ended $r 'testCondition'
    }

    # 并行会审（ALL）：4 个部门 → 同时 4 个任务；全部完成才合流
    'testParallel' = {
        $depts = @('58FE8668233B422FB69EE575F5F402A5','192F606172CB4405AFD3FA1976CE4098',
                   '85122F49DC8A4626A1B870FAD25C8CF8','29D5380BCE6A41AD935D4275DCF40DEF')
        $tpl = TemplateId 'testParallel'
        $start = Post-Ok '/workflow/handle/startFlow' @{ templateId = $tpl; variables = @{ jointDepts = $depts } } 'superAdmin' '发起流程(testParallel)'
        $pi = $start.data.procInsId; $defId = $start.data.procDefId
        # 先完成「责任部门审批」，才会进入并行节点
        $first = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
        $null = Post-Ok '/biz/flow/submit' @{ operateType='200'; flowTask=@{
            taskId=$first[0]; procInsId=$pi; taskDefKey=$first[2]; defId=$defId
            comment='回归测试'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } 'superAdmin' '提交责任部门审批'
        Start-Sleep -Seconds 5
        $n = ActiveCount $pi
        Assert-That ($n -eq 4) "testParallel · 勾选 4 个部门 → 同时生成 4 个并行任务（实际 $n）"
        $rows = ActiveTasks $pi
        $noAssignee = @($rows | Where-Object { ($_ -split '\|')[1] -eq '' }).Count
        Assert-That ($noAssignee -eq 0) "testParallel · 所有并行任务的 ASSIGNEE_ 非空（空 $noAssignee 个）"

        # 剩下的一路批完
        for ($i = 0; $i -lt 12 -and -not (Ended $pi); $i++) {
            $rows = ActiveTasks $pi
            if ($rows.Count -eq 0) { Start-Sleep -Seconds 2; continue }
            $p = ($rows | Select-Object -First 1) -split '\|'
            $null = Post-Ok '/biz/flow/submit' @{ operateType='200'; flowTask=@{
                taskId=$p[0]; procInsId=$pi; taskDefKey=$p[2]; defId=$defId
                comment='回归测试'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } (OwnerOf $p[1]) "提交会审任务($($p[1])/$($p[2]))"
            Start-Sleep -Seconds 3
        }
        $ended = Wait-Ended $pi $PollSeconds
        Assert-That ($ended) "testParallel · **全部会审完成后流程办结**（这一步就是曾经的卡死点）"
    }

    # 并行会审（ANY）：任一完成即放行
    'testParallelAny' = {
        $depts = @('58FE8668233B422FB69EE575F5F402A5','192F606172CB4405AFD3FA1976CE4098',
                   '85122F49DC8A4626A1B870FAD25C8CF8')
        $tpl = TemplateId 'testParallelAny'
        $start = Post-Ok '/workflow/handle/startFlow' @{ templateId = $tpl; variables = @{ jointDepts = $depts } } 'superAdmin' '发起流程(testParallelAny)'
        $pi = $start.data.procInsId; $defId = $start.data.procDefId
        $first = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
        $null = Post-Ok '/biz/flow/submit' @{ operateType='200'; flowTask=@{
            taskId=$first[0]; procInsId=$pi; taskDefKey=$first[2]; defId=$defId
            comment='回归测试'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } 'superAdmin' '提交责任部门审批'
        Start-Sleep -Seconds 5
        Assert-That ((ActiveCount $pi) -eq 3) "testParallelAny · 3 个部门 → 3 个并行任务"
        # 只完成 1 个
        $p = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
        $null = Post-Ok '/biz/flow/submit' @{ operateType='200'; flowTask=@{
            taskId=$p[0]; procInsId=$pi; taskDefKey=$p[2]; defId=$defId
            comment='回归测试'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } (OwnerOf $p[1]) "提交会审任务($($p[1])/$($p[2]))"
        $ended = Wait-Ended $pi $PollSeconds
        Assert-That ($ended) "testParallelAny · **只完成 1 个即合流办结**（ANY 语义）"
    }

    # 首位会签（修复前连启动都失败）+ 或签
    'testSerial' = {
        $tpl = TemplateId 'testSerial'
        $start = Post '/workflow/handle/startFlow' @{ templateId = $tpl; variables = @{} } 'superAdmin'
        Assert-That ($start.code -eq 200) "testSerial · **首节点为会签也能发起**（修复前：流程启动失败）"
        if ($start.code -ne 200) { return }
        $pi = $start.data.procInsId; $defId = $start.data.procDefId
        $script:LastProcInsId = $pi; $script:LastProcDefId = $defId   # 失败时 Show-Diag 用
        Start-Sleep -Seconds 5
        $n = ActiveCount $pi
        Assert-That ($n -eq 2) "testSerial · 会签 2 人 → 2 个任务（实际 $n）"
        $noAssignee = @((ActiveTasks $pi) | Where-Object { ($_ -split '\|')[1] -eq '' }).Count
        Assert-That ($noAssignee -eq 0) "testSerial · 会签任务 ASSIGNEE_ 非空（修复前全为 NULL，谁都看不到）"

        # 会签：两人都批
        for ($i = 0; $i -lt 2; $i++) {
            $p = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
            if (-not $p[0]) { break }
            $null = Post-Ok '/biz/flow/submit' @{ operateType='200'; flowTask=@{
                taskId=$p[0]; procInsId=$pi; taskDefKey=$p[2]; defId=$defId
                comment='会签同意'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } (OwnerOf $p[1]) "提交会签任务($($p[1])/$($p[2]))"
            Start-Sleep -Seconds 4
        }
        # 或签：只批 1 个
        $p = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
        Assert-That ($p[2] -eq 'n2') "testSerial · 会签完成后推进到「分管领导或签」（实际节点 $($p[2])）"
        if ($p[0]) {
            $null = Post-Ok '/biz/flow/submit' @{ operateType='200'; flowTask=@{
                taskId=$p[0]; procInsId=$pi; taskDefKey=$p[2]; defId=$defId
                comment='或签同意'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } (OwnerOf $p[1]) "提交或签任务($($p[1])/$($p[2]))"
        }
        Assert-That (Wait-Ended $pi $PollSeconds) "testSerial · **或签 1 人同意即放行并办结**"
    }

    # 组合：条件 + 并行(ALL) + 固定必经节点
    'testCombo' = {
        $depts = @('58FE8668233B422FB69EE575F5F402A5','192F606172CB4405AFD3FA1976CE4098',
                   '85122F49DC8A4626A1B870FAD25C8CF8')
        $r = Run-Flow -DefKey 'testCombo' -Variables @{ contractType='经营'; amount=20000; jointDepts=$depts }
        Assert-That ($r.Nodes.ContainsKey('n2')) 'testCombo · 条件走「经营」分支 → 经发负责人'
        Assert-That ($r.Nodes.ContainsKey('n5')) 'testCombo · 并行之后仍经过「法务」固定必经节点'
        Assert-Ended $r 'testCombo'
    }
}

# ------------------------------------------------------------------ 主流程

Step '前置检查'
foreach ($p in @(3306, 6379, 5672, 8080)) {
    $up = [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
    if (-not $up) { throw "端口 $p 未监听 —— 请先启动环境（MySQL/Redis/RabbitMQ/后端）" }
}
Ok 'MySQL 3306 / Redis 6379 / RabbitMQ 5672 / 后端 8080 均在线'
if (-not (Test-Path $MySqlCli)) { throw "找不到 mysql 客户端：$MySqlCli" }

# 凭据前置校验（2026-10-05 加固）：token 过期会让审批被静默拒绝（HTTP 200 + code=401），
# 必须在跑用例之前就红，否则会误报成"多实例不合流、流程卡死"。
try {
    Assert-Tokens
    Ok '5 个账号 token 有效（/system/user/profile 全部 code=200）'
} catch {
    Bad $_.Exception.Message
    $script:Fail++
    $script:Failures += '账号凭据无效（本次未执行任何用例）'
    Say ''
    Say '=================================================='
    Say ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
    Say '失败明细：'
    $script:Failures | ForEach-Object { Say "  - $_" }
    exit 1
}

$names = if ($Only.Count -gt 0) { $Only } else { @($cases.Keys) }
foreach ($name in $names) {
    if (-not $cases.Contains($name)) { Bad "未知用例：$name"; $script:Fail++; continue }
    Step "用例 $name"
    $failBefore = $script:Fail
    try { & $cases[$name] } catch { Bad "$name · 异常：$($_.Exception.Message)"; $script:Fail++; $script:Failures += "$name 异常" }
    if ($script:Fail -gt $failBefore) { Show-Diag }
}

Say ''
Say '=================================================='
Say ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Say '失败明细：'
    $script:Failures | ForEach-Object { Say "  - $_" }
    exit 1
}
Say '全部用例通过 —— 简化流程组合矩阵无回归'
exit 0
