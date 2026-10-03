# 为 RuoYi-Vue-OA 准备 RabbitMQ 运行环境（本机无 Docker、WSL 无发行版）
#  - Erlang/OTP 26.2.5.3 静默安装到 C:\Tools\erl-26.2.5.3   （erlang.org 直连可下）
#  - RabbitMQ 3.12.14 使用 Windows 绿色 zip，解压到 C:\Tools （github.com 需走本机代理）
# 选 3.12.x 而非 4.x：应用侧是 Spring Boot 2.5.15 / amqp-client 5.12.0，3.12 完全兼容。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Cache   = 'H:\dsh\ruoyiOA\.cache'
$Tools   = 'C:\Tools'
$ErlDir  = Join-Path $Tools 'erl-26.2.5.3'
$ErlExe  = Join-Path $Cache 'otp_win64_26.2.5.3.exe'
$RmqZip  = Join-Path $Cache 'rabbitmq-server-windows-3.12.14.zip'
$RmqDir  = Join-Path $Tools 'rabbitmq_server-3.12.14'
$Proxy   = 'http://127.0.0.1:7899'

New-Item -ItemType Directory -Force -Path $Cache | Out-Null

function Get-File($url, $out, $useProxy) {
    if ((Test-Path $out) -and ((Get-Item $out).Length -gt 1MB)) {
        Write-Host "已存在，跳过下载: $out"
        return
    }
    Write-Host "下载 $url  (proxy=$useProxy)"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $a = @{ Uri = $url; OutFile = $out; TimeoutSec = 3600; UseBasicParsing = $true; Headers = @{ 'User-Agent' = 'dsh' } }
    if ($useProxy) { $a.Proxy = $Proxy }
    Invoke-WebRequest @a
    Write-Host ("  完成 {0:N1} MB / {1:N0}s" -f ((Get-Item $out).Length / 1MB), $sw.Elapsed.TotalSeconds)
}

# 1) Erlang
Get-File 'https://erlang.org/download/otp_win64_26.2.5.3.exe' $ErlExe $false
if (-not (Test-Path (Join-Path $ErlDir 'bin\erl.exe'))) {
    Write-Host "静默安装 Erlang -> $ErlDir"
    # NSIS: /S 静默；/D 必须放最后且不加引号
    $p = Start-Process -FilePath $ErlExe -ArgumentList '/S', "/D=$ErlDir" -Wait -PassThru
    Write-Host "  安装进程退出码: $($p.ExitCode)"
    Start-Sleep -Seconds 3
} else {
    Write-Host "Erlang 已安装: $ErlDir"
}
if (-not (Test-Path (Join-Path $ErlDir 'bin\erl.exe'))) { throw "Erlang 安装失败，未找到 $ErlDir\bin\erl.exe" }

# 2) RabbitMQ
Get-File 'https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.12.14/rabbitmq-server-windows-3.12.14.zip' $RmqZip $true
if (-not (Test-Path (Join-Path $RmqDir 'sbin\rabbitmq-server.bat'))) {
    Write-Host "解压 RabbitMQ -> $Tools"
    Expand-Archive -Path $RmqZip -DestinationPath $Tools -Force
} else {
    Write-Host "RabbitMQ 已解压: $RmqDir"
}
if (-not (Test-Path (Join-Path $RmqDir 'sbin\rabbitmq-server.bat'))) { throw "RabbitMQ 解压失败: $RmqDir" }

Write-Host ''
Write-Host "ERLANG_HOME   = $ErlDir"
Write-Host "RABBITMQ_HOME = $RmqDir"
& (Join-Path $ErlDir 'bin\erl.exe') -noshell -eval 'io:format("erlang otp ~s~n",[erlang:system_info(otp_release)]), halt().' 2>&1 | Out-String | Write-Host
Write-Host 'READY'
