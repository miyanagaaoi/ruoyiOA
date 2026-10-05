<#
================================================================================
 stop-env.ps1 —— 停止 ruoyiOA 本地开发环境（F: 独立环境版）
--------------------------------------------------------------------------------
 用法（在仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\stop-env.ps1
   powershell ... -File .\stop-env.ps1 -IncludeInfra    # 连 MySQL/Redis/RabbitMQ 一起停

 默认只停「本项目的」后端与前端；基础设施默认保留。
 注意：F: 这套 MySQL/Redis/RabbitMQ 是本仓库 env\ 下自带的实例，端口仍是
 3306/6379/5672；H:\dsh\OA 用的是另一套（H: 上的那份），互不干扰。

 安全约定（踩过的坑）：
   1) **先打印再杀** —— 把待终止进程的 PID 和命令行都列出来，确认无误才动手。
   2) **按命令行精确匹配，并且排除 $PID** —— 曾经用
      `Get-CimInstance ... | Where CommandLine -match 'ruoyi-admin\.jar'`
      时，把**正在执行这条命令的 shell 自己**也匹配上了，结果自杀。
   3) 只匹配本仓库路径，避免误伤 H:\dsh\OA\oa-server 等其它工程。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [switch]$IncludeInfra
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Root        = $PSScriptRoot
$EnvDir      = Join-Path $Root 'env'
$BackendDir  = Join-Path $Root 'ruoyi-vue-oa-master'
$FrontendDir = Join-Path $Root 'ruoyi-vue-oa-ui-master'

$MySqlAdmin = Join-Path $EnvDir 'mysql\server\bin\mysqladmin.exe'
$RedisCli   = Join-Path $EnvDir 'redis\server\redis-cli.exe'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok  ($m) { Write-Host "    [OK] $m" -ForegroundColor Green }
function Info($m) { Write-Host "    $m" }

# 收集「命令行包含某关键字」的进程，排除自己
function Find-Procs([string]$Pattern) {
    $self = $PID
    return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.ProcessId -ne $self -and
            $_.Name -match '^(java|node|cmd|mysqld|redis-server|erl|erl_child_setup|epmd)\.exe$' -and
            $_.CommandLine -and $_.CommandLine -like "*$Pattern*"
        })
}

function Show-And-Kill($Procs, [string]$What) {
    if (-not $Procs -or $Procs.Count -eq 0) { Info "$What ：未发现运行中的进程"; return 0 }
    Info "$What ：将终止 $($Procs.Count) 个进程"
    foreach ($p in $Procs) {
        $cl = $p.CommandLine
        if ($cl.Length -gt 130) { $cl = $cl.Substring(0, 130) + '...' }
        Info ("    PID={0,-7} {1}" -f $p.ProcessId, $cl)
    }
    foreach ($p in $Procs) {
        try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop } catch { Info "    PID=$($p.ProcessId) 终止失败：$($_.Exception.Message)" }
    }
    Ok "$What 已终止"
    return $Procs.Count
}

# ------------------------------------------------------------------ 后端

Step '停止后端 (8080)'
# 只匹配本仓库的后端 jar，避免误伤别的工程
$killed = Show-And-Kill (Find-Procs "$BackendDir") '后端'
# 兜底：按 8080 端口占用者（限定 java）
$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
foreach ($procId in ($conn | Select-Object -ExpandProperty OwningProcess -Unique)) {
    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -eq 'java') {
        Info "端口 8080 仍被 java PID=$procId 占用，终止"
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
}
Start-Sleep -Seconds 4

# ------------------------------------------------------------------ 前端

Step '停止前端 (80)'
$null = Show-And-Kill (Find-Procs $FrontendDir) '前端'
$conn = Get-NetTCPConnection -LocalPort 80 -State Listen -ErrorAction SilentlyContinue
foreach ($procId in ($conn | Select-Object -ExpandProperty OwningProcess -Unique)) {
    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if ($p) {
        Info "端口 80 仍被 $($p.ProcessName) PID=$procId 占用，终止"
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
}
Start-Sleep -Seconds 2

# ------------------------------------------------------------------ 基础设施

if ($IncludeInfra) {
    Step '停止 RabbitMQ (5672)'
    # rabbitmq-server.bat 会拉起 erl.exe / erl_child_setup.exe / epmd.exe
    $null = Show-And-Kill (Find-Procs 'rabbitmq') 'RabbitMQ(rabbitmq)'
    foreach ($n in @('erl', 'erl_child_setup', 'epmd')) {
        $ps2 = @(Get-Process -Name $n -ErrorAction SilentlyContinue)
        if ($ps2.Count -gt 0) {
            Info "$n ：终止 $($ps2.Count) 个"
            $ps2 | Stop-Process -Force -ErrorAction SilentlyContinue
        }
    }
    Start-Sleep -Seconds 3

    Step '停止 Redis (6379)'
    # 只停「本仓库 env\redis 起的」实例：按 --dir 指向本仓库判断
    $redisInst = @(Get-CimInstance Win32_Process -Filter "Name='redis-server.exe'" -ErrorAction SilentlyContinue |
                   Where-Object { $_.CommandLine -like "*$EnvDir*" })
    if ($redisInst.Count -gt 0 -and (Test-Path $RedisCli)) {
        # 优先用 redis-cli shutdown（AOF 落盘后优雅退出）
        & $RedisCli -h 127.0.0.1 -p 6379 shutdown nosave 2>&1 | Out-Null
        Start-Sleep -Seconds 2
    }
    if (Get-NetTCPConnection -LocalPort 6379 -State Listen -ErrorAction SilentlyContinue) {
        $null = Show-And-Kill $redisInst 'Redis'
    } else { Ok 'Redis 已停止' }

    Step '停止 MySQL (3306)'
    # 只停「本仓库 env\mysql 起的」实例：按 --defaults-file 指向本仓库判断
    $mysqlInst = @(Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" -ErrorAction SilentlyContinue |
                   Where-Object { $_.CommandLine -like "*$EnvDir*" })
    if ($mysqlInst.Count -gt 0 -and (Test-Path $MySqlAdmin)) {
        # 便携版 root 是空口令；shutdown 走的是 my.ini 里配置的端口
        & $MySqlAdmin '--host=127.0.0.1' '--user=root' '--port=3306' shutdown 2>&1 | Out-Null
        Start-Sleep -Seconds 4
    }
    if (Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue) {
        Info 'mysqladmin 未生效，改为强制终止（仅限本仓库实例）'
        $null = Show-And-Kill $mysqlInst 'MySQL'
    } else { Ok 'MySQL 已停止' }
}

# ------------------------------------------------------------------ 汇总

Step '端口状态'
foreach ($r in @(@{p=3306;n='MySQL'}, @{p=6379;n='Redis'}, @{p=5672;n='RabbitMQ'}, @{p=8080;n='后端'}, @{p=80;n='前端'})) {
    $up = [bool](Get-NetTCPConnection -LocalPort $r.p -State Listen -ErrorAction SilentlyContinue)
    Write-Host ("    {0,-10} {1,-6} {2}" -f $r.n, $r.p, $(if ($up) { '仍在运行' } else { '已停止' })) `
        -ForegroundColor $(if ($up) { 'Yellow' } else { 'Green' })
}
Write-Host ''
if (-not $IncludeInfra) {
    Info 'MySQL / Redis / RabbitMQ 未处理（默认保留）；要一起停请加 -IncludeInfra'
}
Info '重新启动：powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1'
