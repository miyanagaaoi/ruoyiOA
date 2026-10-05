#requires -Version 5.1
<#
  locked-run.ps1 —— 全仓共享资源的互斥执行器（AgentTeams 并发协作基础设施）

  用途：多个成员同时改同一个仓库时，Maven 构建 / 后端重启 / 接口验收脚本 / Playwright
        都会争抢同一份 target 目录、同一个 8080 进程或同一份数据库夹具。
        本脚本用「独占文件句柄」把这类命令串行化，避免互相踩踏。

  用法：
    powershell -NoProfile -ExecutionPolicy Bypass -File F:\dsh\ruoyiOA\tools\locked-run.ps1 `
      -LockName build -Command "cd F:\dsh\ruoyiOA\ruoyi-vue-oa-master; mvn -B -pl ruoyi-ctms test"

    -LockName build  → Maven 构建 / 编译 / 单测（默认）
    -LockName env    → 后端打包重启 + 接口脚本 + playwright + 改数据库夹具

  语义：
    * 拿到锁才执行；拿不到就每 5 秒重试，超时（默认 30 分钟）退出码 97。
    * 命令的退出码原样透传（0 = 成功）。
    * 锁文件：F:\dsh\ruoyiOA\.cache\locks\<LockName>.lock（.cache 已 gitignore）。
    * 进程异常退出时文件句柄由操作系统释放，不会留下死锁。
#>
param(
  [Parameter(Mandatory = $true)][string]$Command,
  [ValidateSet('build', 'env')][string]$LockName = 'build',
  [int]$TimeoutMinutes = 30,
  [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

function Write-Log([string]$Message) {
  if (-not $Quiet) { Write-Host ("[locked-run:{0}] {1}" -f $LockName, $Message) }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$lockDir = Join-Path $repoRoot '.cache\locks'
if (-not (Test-Path $lockDir)) { New-Item -ItemType Directory -Force -Path $lockDir | Out-Null }
$lockPath = Join-Path $lockDir ("{0}.lock" -f $LockName)

$watch = [System.Diagnostics.Stopwatch]::StartNew()
$stream = $null
while ($null -eq $stream) {
  try {
    $stream = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::OpenOrCreate,
                                      [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
  } catch {
    if ($watch.Elapsed.TotalMinutes -ge $TimeoutMinutes) {
      Write-Host ("[locked-run:{0}] 获取锁超时（{1} 分钟）：{2}" -f $LockName, $TimeoutMinutes, $lockPath)
      exit 97
    }
    if ($watch.Elapsed.TotalSeconds -lt 5) { Write-Log ("等待锁中（{0}）…" -f $lockPath) }
    Start-Sleep -Seconds 5
  }
}

$exitCode = 1
try {
  Write-Log ("已获得锁（等待 {0:N1} 秒），开始执行" -f $watch.Elapsed.TotalSeconds)
  Write-Log ("命令：{0}" -f $Command)
  $global:LASTEXITCODE = 0
  & ([scriptblock]::Create($Command))
  $native = $LASTEXITCODE
  if ($null -eq $native) { $native = 0 }
  $exitCode = [int]$native
  if ($exitCode -eq 0 -and -not $?) { $exitCode = 1 }
} catch {
  Write-Host ("[locked-run:{0}] 执行抛异常：{1}" -f $LockName, $_.Exception.Message)
  $exitCode = 1
} finally {
  if ($null -ne $stream) { $stream.Dispose() }
  Write-Log ("执行结束，退出码 {0}，耗时 {1:N1} 秒" -f $exitCode, $watch.Elapsed.TotalSeconds)
}
exit $exitCode
