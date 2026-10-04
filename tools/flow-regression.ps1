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
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MySqlCli = 'H:\dsh\OA\.cache\mysql\extract\mysql-8.0.40-winx64\bin\mysql.exe',
    [string]$CacheDir = 'H:\dsh\ruoyiOA\.cache',
    [string]$Database = 'rad_oa',
    [string[]]$Only = @(),
    [int]$PollSeconds = 60
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

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

    $start = Post '/workflow/handle/startFlow' @{ templateId = $tpl; variables = $Variables } $Starter
    if ($start.code -ne 200) { throw "发起失败：$($start.msg)" }
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
        $null = Post '/biz/flow/submit' @{
            operateType = '200'
            flowTask = @{
                taskId = $tid; procInsId = $pi; taskDefKey = $key; defId = $defId
                comment = "回归测试($owner)"; templateId = $tpl
                templateType = (Sql "SELECT type FROM t_template WHERE id='$tpl'")
                type = 'TODO'; handleType = 'AUDIT'; variables = @{}
            }
        } $owner
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

    # 条件分支：两条条件同时为真时只能走一条（分支顺序即优先级），且必须走排他网关
    'testCondition' = {
        $r = Run-Flow -DefKey 'testCondition' -Variables @{ contractType = '经营'; amount = 5000 }
        Assert-That ($r.Nodes.ContainsKey('n2')) 'testCondition · 命中「经营」分支 → 经发部审批'
        Assert-That (-not $r.Nodes.ContainsKey('n3')) 'testCondition · 未命中「金额>10000」分支 → 未走财务部'
        Assert-Ended $r 'testCondition'
    }

    # 并行会审（ALL）：4 个部门 → 同时 4 个任务；全部完成才合流
    'testParallel' = {
        $depts = @('58FE8668233B422FB69EE575F5F402A5','192F606172CB4405AFD3FA1976CE4098',
                   '85122F49DC8A4626A1B870FAD25C8CF8','29D5380BCE6A41AD935D4275DCF40DEF')
        $tpl = TemplateId 'testParallel'
        $start = Post '/workflow/handle/startFlow' @{ templateId = $tpl; variables = @{ jointDepts = $depts } } 'superAdmin'
        $pi = $start.data.procInsId; $defId = $start.data.procDefId
        # 先完成「责任部门审批」，才会进入并行节点
        $first = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
        $null = Post '/biz/flow/submit' @{ operateType='200'; flowTask=@{
            taskId=$first[0]; procInsId=$pi; taskDefKey=$first[2]; defId=$defId
            comment='回归测试'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } 'superAdmin'
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
            $null = Post '/biz/flow/submit' @{ operateType='200'; flowTask=@{
                taskId=$p[0]; procInsId=$pi; taskDefKey=$p[2]; defId=$defId
                comment='回归测试'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } (OwnerOf $p[1])
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
        $start = Post '/workflow/handle/startFlow' @{ templateId = $tpl; variables = @{ jointDepts = $depts } } 'superAdmin'
        $pi = $start.data.procInsId; $defId = $start.data.procDefId
        $first = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
        $null = Post '/biz/flow/submit' @{ operateType='200'; flowTask=@{
            taskId=$first[0]; procInsId=$pi; taskDefKey=$first[2]; defId=$defId
            comment='回归测试'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } 'superAdmin'
        Start-Sleep -Seconds 5
        Assert-That ((ActiveCount $pi) -eq 3) "testParallelAny · 3 个部门 → 3 个并行任务"
        # 只完成 1 个
        $p = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
        $null = Post '/biz/flow/submit' @{ operateType='200'; flowTask=@{
            taskId=$p[0]; procInsId=$pi; taskDefKey=$p[2]; defId=$defId
            comment='回归测试'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } (OwnerOf $p[1])
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
        Start-Sleep -Seconds 5
        $n = ActiveCount $pi
        Assert-That ($n -eq 2) "testSerial · 会签 2 人 → 2 个任务（实际 $n）"
        $noAssignee = @((ActiveTasks $pi) | Where-Object { ($_ -split '\|')[1] -eq '' }).Count
        Assert-That ($noAssignee -eq 0) "testSerial · 会签任务 ASSIGNEE_ 非空（修复前全为 NULL，谁都看不到）"

        # 会签：两人都批
        for ($i = 0; $i -lt 2; $i++) {
            $p = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
            if (-not $p[0]) { break }
            $null = Post '/biz/flow/submit' @{ operateType='200'; flowTask=@{
                taskId=$p[0]; procInsId=$pi; taskDefKey=$p[2]; defId=$defId
                comment='会签同意'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } (OwnerOf $p[1])
            Start-Sleep -Seconds 4
        }
        # 或签：只批 1 个
        $p = (ActiveTasks $pi | Select-Object -First 1) -split '\|'
        Assert-That ($p[2] -eq 'n2') "testSerial · 会签完成后推进到「分管领导或签」（实际节点 $($p[2])）"
        if ($p[0]) {
            $null = Post '/biz/flow/submit' @{ operateType='200'; flowTask=@{
                taskId=$p[0]; procInsId=$pi; taskDefKey=$p[2]; defId=$defId
                comment='或签同意'; templateId=$tpl; type='TODO'; handleType='AUDIT'; variables=@{} } } (OwnerOf $p[1])
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

$names = if ($Only.Count -gt 0) { $Only } else { @($cases.Keys) }
foreach ($name in $names) {
    if (-not $cases.Contains($name)) { Bad "未知用例：$name"; $script:Fail++; continue }
    Step "用例 $name"
    try { & $cases[$name] } catch { Bad "$name · 异常：$($_.Exception.Message)"; $script:Fail++; $script:Failures += "$name 异常" }
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
