<#
================================================================================
 start-env.ps1 —— 一键启动 ruoyiOA 本地开发环境（F: 独立环境版）
--------------------------------------------------------------------------------
 启动顺序：MySQL 3306 → Redis 6379 → RabbitMQ 5672 → 后端 8080 → 前端 80

 用法（在仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\start-env.ps1
   powershell ... -File .\start-env.ps1 -SkipFrontend      # 只起基础设施 + 后端
   powershell ... -File .\start-env.ps1 -Rebuild           # 先重新打包后端再起
   powershell ... -File .\start-env.ps1 -Only Infra        # 只起 MySQL/Redis/MQ

 停止：.\stop-env.ps1    （加 -IncludeInfra 连基础设施一起停）

 2026-10-05 迁移说明（H:\dsh\ruoyiOA → F:\dsh\ruoyiOA）：
   本项目原先借用 H:\dsh\OA\oa-deploy\runtime\start-local.ps1 起 MySQL/Redis，
   与 H:\dsh\OA 共用同一套便携版运行时和同一个数据库实例。迁移时已把运行时
   与**全部数据库数据**复制到本仓库 .\env\ 下，F: 环境完全自洽，不再触碰 H:。

 设计要点（都是踩过的坑）：
   1) **必须用 powershell 而不是 pwsh** —— 本机没有 pwsh，只有 Windows PowerShell 5.1。
   2) **本脚本自身必须存成 UTF-8 with BOM** —— 5.1 读无 BOM 的 UTF-8 会把中文注释
      当 ANSI 解码，直接导致解析失败（"Unexpected token"）。
   3) **拉起常驻进程用 WMI（Win32_Process.Create）而不是 Start-Process** ——
      Start-Process 创建的子进程仍继承调用方的控制台/管道句柄，当调用方是按管道读
      输出的宿主（IDE、CI、Agent 的 shell 包装器）时，脚本退了管道却不关，
      表现为"命令挂住不返回"。
   4) MySQL / Redis 的便携版、my.ini、data 目录都在 .\env\ 内（见 env\README.md）。
   5) 所有服务都**幂等**：端口已在监听就跳过，可反复执行。

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [ValidateSet('All', 'Infra', 'Backend', 'Frontend')]
    [string]$Only = 'All',
    [switch]$SkipFrontend,
    [switch]$SkipBackend,
    [switch]$Rebuild,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Root       = $PSScriptRoot
$Cache      = Join-Path $Root '.cache'
$Logs       = Join-Path $Root 'logs'
$EnvDir     = Join-Path $Root 'env'
$BackendDir = Join-Path $Root 'ruoyi-vue-oa-master'
$FrontendDir= Join-Path $Root 'ruoyi-vue-oa-ui-master'
$BackendJar = Join-Path $BackendDir 'ruoyi-admin\target\ruoyi-admin.jar'
$JavaExe    = 'D:\Program Files\Java\jdk-11\bin\java.exe'

# ---- 本仓库自带的便携版基础设施（F: 独立环境）----
$MySqlHome  = Join-Path $EnvDir 'mysql\server'
$MySqlExe   = Join-Path $MySqlHome 'bin\mysqld.exe'
$MySqlCli   = Join-Path $MySqlHome 'bin\mysql.exe'
$MyIni      = Join-Path $EnvDir 'mysql\my.ini'
$RedisHome  = Join-Path $EnvDir 'redis\server'
$RedisExe   = Join-Path $RedisHome 'redis-server.exe'
$RedisCli   = Join-Path $RedisHome 'redis-cli.exe'
$RedisData  = Join-Path $EnvDir 'redis\data'

$RabbitScript  = Join-Path $Root 'tools\start-rabbitmq.ps1'

New-Item -ItemType Directory -Force -Path $Cache, $Logs | Out-Null

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok  ($m) { Write-Host "    [OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "    [!]  $m" -ForegroundColor Yellow }
function Die ($m) { Write-Host "    [XX] $m" -ForegroundColor Red; exit 1 }

function Test-Port([int]$Port, [int]$TimeoutMs = 900) {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $t = $c.ConnectAsync('127.0.0.1', $Port)
        if ($t.Wait($TimeoutMs) -and $c.Connected) { return $true }
        return $false
    } catch { return $false } finally { $c.Dispose() }
}

function Wait-Port([int]$Port, [int]$Seconds = 90, [string]$What = 'service') {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

# 用 WMI 拉起「完全脱离调用方句柄」的进程（见文件头 设计要点 3）
function Start-Detached([string]$CmdFile) {
    $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = "cmd.exe /c `"$CmdFile`""
    }
    if ($r.ReturnValue -ne 0) { throw "WMI 创建进程失败：ReturnValue=$($r.ReturnValue)  $CmdFile" }
    return [int]$r.ProcessId
}

# ------------------------------------------------------------------ 0) 前置检查

Step '前置检查'
if (-not (Test-Path $BackendDir))  { Die "找不到后端工程：$BackendDir" }
if (-not (Test-Path $JavaExe))     { Die "找不到 JDK 11：$JavaExe（后端必须用 JDK 11 运行）" }
Ok "仓库根目录：$Root"
Ok "自带运行时：$EnvDir"

# ------------------------------------------------------------------ 1) MySQL

if ($Only -in @('All', 'Infra')) {
    Step 'MySQL (3306)'
    if (Test-Port 3306) {
        Ok '已在运行，跳过'
    } else {
        if (-not (Test-Path $MySqlExe)) { Die "找不到 MySQL：$MySqlExe（应位于 env\mysql\server）" }
        if (-not (Test-Path $MyIni))    { Die "找不到 my.ini：$MyIni" }
        $mysqlConsole = Join-Path $EnvDir 'mysql\console.log'
        $cmdFile      = Join-Path $Cache 'run-mysql.cmd'
        $body = "@echo off`r`n" +
                "cd /d `"$MySqlHome`"`r`n" +
                "`"$MySqlExe`" --defaults-file=`"$MyIni`" > `"$mysqlConsole`" 2>&1`r`n"
        Set-Content -Path $cmdFile -Value $body -Encoding ascii
        $pidM = Start-Detached $cmdFile
        Write-Host "    已拉起 (wrapper PID=$pidM)，等待 3306 就绪 ..."
        if (-not (Wait-Port 3306 90 'MySQL')) {
            Warn '端口未就绪，错误日志尾部：'
            $errLog = Join-Path $EnvDir 'mysql\error.log'
            if (Test-Path $errLog) { Get-Content $errLog -Tail 25 | ForEach-Object { "      $_" } }
            Die 'MySQL 90 秒内未监听 3306'
        }
        Ok 'MySQL 3306 已就绪'
    }
    # 就绪校验（root 空口令；数据目录是从 H: 整份复制过来的，账号与库原样保留）
    $ver = & $MySqlCli -u root -N -B -e "SELECT CONCAT(VERSION(),' / ',@@character_set_server,' / ',@@collation_server,' / ',@@time_zone);" 2>$null
    if ($ver) { Ok "版本参数：$ver" }
}

# ------------------------------------------------------------------ 2) Redis

if ($Only -in @('All', 'Infra')) {
    Step 'Redis (6379)'
    if (Test-Port 6379) {
        Ok '已在运行，跳过'
    } else {
        if (-not (Test-Path $RedisExe)) { Die "找不到 Redis：$RedisExe（应位于 env\redis\server）" }
        New-Item -ItemType Directory -Force -Path $RedisData | Out-Null
        $redisLog = Join-Path $EnvDir 'redis\redis.log'
        $cmdFile  = Join-Path $Cache 'run-redis.cmd'
        $body = "@echo off`r`n" +
                "cd /d `"$RedisHome`"`r`n" +
                "`"$RedisExe`" --port 6379 --bind 127.0.0.1 --dir `"$RedisData`" " +
                "--appendonly yes --logfile `"$redisLog`" > NUL 2>&1`r`n"
        Set-Content -Path $cmdFile -Value $body -Encoding ascii
        $pidR = Start-Detached $cmdFile
        Write-Host "    已拉起 (wrapper PID=$pidR)，等待 6379 就绪 ..."
        if (-not (Wait-Port 6379 30 'Redis')) { Die 'Redis 30 秒内未监听 6379' }
        Ok 'Redis 6379 已就绪'
    }
    $pong = & $RedisCli -h 127.0.0.1 -p 6379 ping 2>$null
    if ($pong) { Ok "PING -> $pong" }
}

# ------------------------------------------------------------------ 3) RabbitMQ

if ($Only -in @('All', 'Infra')) {
    Step 'RabbitMQ (5672)'
    if (Test-Port 5672) {
        Ok '已在运行，跳过'
    } else {
        if (-not (Test-Path $RabbitScript)) { Die "找不到 $RabbitScript" }
        & powershell -NoProfile -ExecutionPolicy Bypass -File $RabbitScript 2>&1 |
            ForEach-Object { "    $_" }
        if (-not (Wait-Port 5672 120 'RabbitMQ')) { Die 'RabbitMQ 120 秒内未监听 5672（看 logs\rabbitmq.log）' }
        Ok 'RabbitMQ 5672 已就绪'
    }
    Warn 'MQ 是硬依赖：关掉它，/biz/form/save 的「我起草的 / 新增待办」会直接报错'
}

# ------------------------------------------------------------------ 4) 后端

if (($Only -in @('All', 'Backend')) -and -not $SkipBackend) {
    Step '后端 (8080)'
    if (Test-Port 8080) {
        Ok '8080 已在监听，跳过（如需重启先跑 .\stop-env.ps1）'
    } else {
        if ($Rebuild -or -not (Test-Path $BackendJar)) {
            if (-not (Test-Path $BackendJar)) { Warn 'jar 不存在，先打包' } else { Warn '按 -Rebuild 重新打包' }
            Push-Location $BackendDir
            try {
                # 注意要 clean package：模块间依赖会让增量打包把旧的子模块打进 jar
                & mvn -B -q -DskipTests -pl ruoyi-admin clean package 2>&1 |
                    Select-Object -Last 5 | ForEach-Object { "    $_" }
                if ($LASTEXITCODE -ne 0) { Die "打包失败（mvn exit=$LASTEXITCODE）" }
            } finally { Pop-Location }
            Ok ("打包完成 " + [math]::Round((Get-Item $BackendJar).Length / 1MB, 1) + " MB")
        }

        $backendLog = Join-Path $Logs 'backend-run.log'
        $cmdFile    = Join-Path $Cache 'run-backend.cmd'
        # 经 cmd 包装再重定向，避免子进程继承调用方句柄
        $body = "@echo off`r`n" +
                "`"$JavaExe`" -jar `"$BackendJar`" > `"$backendLog`" 2>&1`r`n"
        Set-Content -Path $cmdFile -Value $body -Encoding ascii

        $pid2 = Start-Detached $cmdFile
        Write-Host "    已拉起 (wrapper PID=$pid2)，等待 8080 就绪 ..."
        if (-not (Wait-Port 8080 $TimeoutSeconds 'backend')) {
            Warn '端口未就绪，日志尾部：'
            if (Test-Path $backendLog) { Get-Content $backendLog -Tail 25 | ForEach-Object { "      $_" } }
            Die "后端 $TimeoutSeconds 秒内未监听 8080"
        }
        Ok "后端已就绪：http://127.0.0.1:8080   日志 $backendLog"
    }
}

# ------------------------------------------------------------------ 5) 前端

if (($Only -in @('All', 'Frontend')) -and -not $SkipFrontend) {
    Step '前端 dev server (80)'
    if (Test-Port 80) {
        Ok '80 已在监听，跳过'
    } else {
        if (-not (Test-Path $FrontendDir)) { Die "找不到前端工程：$FrontendDir" }
        $uiLog   = Join-Path $Logs 'ui-dev.log'
        $cmdFile = Join-Path $Cache 'run-frontend.cmd'
        # 必须用 npm.cmd：npm.ps1 会被执行策略拦下（exit 1 且日志为空）
        $body = "@echo off`r`n" +
                "cd /d `"$FrontendDir`"`r`n" +
                "call npm.cmd run dev > `"$uiLog`" 2>&1`r`n"
        Set-Content -Path $cmdFile -Value $body -Encoding ascii

        $pid3 = Start-Detached $cmdFile
        Write-Host "    已拉起 (wrapper PID=$pid3)，等待编译 ..."
        if (-not (Wait-Port 80 $TimeoutSeconds 'frontend')) { Die "前端 $TimeoutSeconds 秒内未监听 80" }

        # 端口监听 ≠ 编译完成，再等 HTTP 真正可用
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            try {
                $resp = Invoke-WebRequest 'http://localhost/' -TimeoutSec 8 -UseBasicParsing
                if ($resp.StatusCode -eq 200 -and $resp.Content -match '<title>') { $ready = $true; break }
            } catch { }
            Start-Sleep -Seconds 5
        }
        if ($ready) {
            Ok "前端已就绪：http://localhost/   （首次编译约 80 秒，日志 $uiLog）"
        } else {
            Warn "端口已监听但页面尚未就绪，稍等或看日志：$uiLog"
        }
    }
}

# ------------------------------------------------------------------ 汇总

Step '环境状态'
$rows = @(
    @{ Port = 3306; Name = 'MySQL' },
    @{ Port = 6379; Name = 'Redis' },
    @{ Port = 5672; Name = 'RabbitMQ' },
    @{ Port = 8080; Name = '后端' },
    @{ Port = 80;   Name = '前端' }
)
foreach ($r in $rows) {
    $up = Test-Port $r.Port
    Write-Host ("    {0,-10} {1,-6} {2}" -f $r.Name, $r.Port, $(if ($up) { '在线' } else { '离线' })) `
        -ForegroundColor $(if ($up) { 'Green' } else { 'Red' })
}
Write-Host ''
Write-Host '  前端页面 : http://localhost/' -ForegroundColor Cyan
Write-Host '  登录账号 : superAdmin / admin123（验证码开启）' -ForegroundColor Cyan
Write-Host '  停止环境 : powershell -NoProfile -ExecutionPolicy Bypass -File .\stop-env.ps1 -IncludeInfra' -ForegroundColor Cyan
Write-Host '  环境说明 : env\README.md' -ForegroundColor Cyan
Write-Host ''
Warn 'token 有有效期；重启后要跑接口脚本，需先重新登录（见 DEV-ENV.md §4）'
