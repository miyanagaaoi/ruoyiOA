# 启动 portable RabbitMQ（WMI 创建进程，完全脱离调用方句柄，脚本立即返回）
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File start-rabbitmq.ps1
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Runner = 'H:\dsh\ruoyiOA\tools\run-rabbitmq.cmd'
$Log    = 'H:\dsh\ruoyiOA\logs\rabbitmq.log'

function Test-Port([int]$Port, [int]$TimeoutMs = 800) {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $t = $c.ConnectAsync('127.0.0.1', $Port)
        if ($t.Wait($TimeoutMs) -and $c.Connected) { return $true }
        return $false
    } catch { return $false } finally { $c.Dispose() }
}

if (Test-Port 5672) { Write-Host '[OK] RabbitMQ 已在运行 (5672)'; exit 0 }

$result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine = "cmd.exe /c `"$Runner`""
}
if ($result.ReturnValue -ne 0) { throw "WMI 创建进程失败: ReturnValue=$($result.ReturnValue)" }
Write-Host "[..] 已拉起 (wrapper PID=$($result.ProcessId))，等待 5672 就绪 ..."

$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
    if (Test-Port 5672) { Write-Host '[OK] RabbitMQ 已就绪: 127.0.0.1:5672'; Write-Host "     日志: $Log"; exit 0 }
    Start-Sleep -Milliseconds 800
}
Write-Host '[!!] 120 秒内未监听 5672，日志尾部：'
if (Test-Path $Log) { Get-Content $Log -Tail 30 }
exit 1
