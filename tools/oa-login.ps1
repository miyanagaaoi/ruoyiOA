<#
================================================================================
 oa-login.ps1 —— 批量登录测试账号并把 token 存到 .cache（供接口脚本使用）
--------------------------------------------------------------------------------
 为什么要它：token 有有效期，重启或隔一段时间后再跑接口脚本（如流程回归）
 就会报「认证失败，无法访问系统资源」。而本项目的登录被图形验证码挡着，
 验证码是**数学算式**且图片扭曲，脚本里没法 OCR。

 做法：临时把 sys.account.captchaEnabled 置 false（**数据库 + Redis 缓存都要改**，
       只改库不改缓存不生效），登录完**立即恢复为 true**。
       恢复放在 finally 里，登录失败也会恢复。

 用法（在仓库根目录）：
   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\oa-login.ps1
   powershell ... -File .\tools\oa-login.ps1 -Users superAdmin,zhangwei
   powershell ... -File .\tools\oa-login.ps1 -Password 'admin123'

 产物：.cache\token-<用户名>.txt（.cache 已在 .gitignore 内，不入库）

 @author 二开
================================================================================
#>
[CmdletBinding()]
param(
    [string[]]$Users = @('superAdmin', 'zhangwei', 'lina', 'wangqiang', 'zhaomin'),
    [string]$Password = 'admin123',
    [string]$BaseUrl  = 'http://localhost:8080',
    # 注意：$PSScriptRoot 在 param 默认值里取不到（参数绑定早于它可用），
    #       所以这两个默认值留空，在脚本体内再补。
    [string]$CacheDir,
    [string]$MySqlCli = 'H:\dsh\OA\.cache\mysql\extract\mysql-8.0.40-winx64\bin\mysql.exe',
    [string]$RedisCli = 'H:\dsh\OA\.cache\redis\redis-5.0.14.1\redis-cli.exe',
    [string]$RsaTool,
    [string]$Database = 'rad_oa'
)

if (-not $CacheDir) { $CacheDir = Join-Path (Split-Path $PSScriptRoot -Parent) '.cache' }
if (-not $RsaTool)  { $RsaTool  = Join-Path $PSScriptRoot 'rsa-encrypt.js' }

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Ok  ($m) { Write-Host "    [OK] $m" -ForegroundColor Green }
function Bad ($m) { Write-Host "    [!!] $m" -ForegroundColor Red }

$CaptchaKey = 'sys_config:sys.account.captchaEnabled'

# 验证码开关：数据库与 Redis 缓存必须同时改，否则应用读到的仍是旧值
function Set-Captcha([string]$value) {
    & $MySqlCli '--host=127.0.0.1' '--user=root' "-D" $Database '--batch' `
        -e "UPDATE sys_config SET config_value='$value' WHERE config_key='sys.account.captchaEnabled';" 2>&1 | Out-Null
    & $RedisCli -h 127.0.0.1 -p 6379 set $CaptchaKey $value 2>&1 | Out-Null
}

Step '前置检查'
foreach ($f in @($MySqlCli, $RedisCli, $RsaTool)) {
    if (-not (Test-Path $f)) { throw "缺少文件：$f" }
}
if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null }
if (-not (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)) {
    throw '后端 8080 未监听 —— 先跑 .\start-env.ps1'
}
Ok "后端在线；token 将写入 $CacheDir"

# 口令要 RSA 加密后再提交
$enc = (node $RsaTool $Password 2>&1).Trim()
if (-not $enc -or $enc.Length -lt 16) { throw "RSA 加密失败，rsa-encrypt.js 输出异常：$enc" }

$okCount = 0
$failList = @()
try {
    Step '临时关闭验证码'
    Set-Captcha 'false'
    Start-Sleep -Seconds 1
    Ok '已关闭（sys.account.captchaEnabled=false）'

    Step "登录 $($Users.Count) 个账号"
    foreach ($u in $Users) {
        $body = @{ username = $u; password = $enc; code = ''; uuid = '' } | ConvertTo-Json -Compress
        try {
            $r = Invoke-RestMethod "$BaseUrl/login" -Method Post -ContentType 'application/json' `
                    -Body $body -TimeoutSec 30
            if ($r.code -eq 200 -and $r.token) {
                [System.IO.File]::WriteAllText((Join-Path $CacheDir "token-$u.txt"), $r.token,
                    (New-Object System.Text.ASCIIEncoding))
                Ok ("{0,-12} 成功（token {1} 字符）" -f $u, $r.token.Length)
                $okCount++
            } else {
                Bad ("{0,-12} 失败 code={1} msg={2}" -f $u, $r.code, $r.msg)
                $failList += $u
            }
        } catch {
            Bad ("{0,-12} 异常：{1}" -f $u, $_.Exception.Message)
            $failList += $u
        }
    }
} finally {
    Step '恢复验证码开关'
    Set-Captcha 'true'
    $rd = & $RedisCli -h 127.0.0.1 -p 6379 get $CaptchaKey 2>&1
    $db = & $MySqlCli '--host=127.0.0.1' '--user=root' "-D" $Database '--batch' '--skip-column-names' `
            -e "SELECT config_value FROM sys_config WHERE config_key='sys.account.captchaEnabled';" 2>&1
    Ok "已恢复：redis=$rd  db=$db"
    if ("$rd".Trim() -ne 'true' -or "$db".Trim() -ne 'true') {
        Write-Host '    [!!] 验证码开关未恢复为 true，请手工检查！' -ForegroundColor Red
    }
}

Write-Host ''
Write-Host ("登录成功 {0} 个，失败 {1} 个" -f $okCount, $failList.Count)
if ($failList.Count -gt 0) { Write-Host ("  失败账号：{0}" -f ($failList -join ', ')) -ForegroundColor Red; exit 1 }

Write-Host '  抽验 token 有效性（校验 code，不只看字段空不空）：' -ForegroundColor Cyan
$t = (Get-Content (Join-Path $CacheDir 'token-superAdmin.txt') -Raw).Trim()
$chk = Invoke-RestMethod "$BaseUrl/workflow/simple-flow/list" -Headers @{ Authorization = "Bearer $t" } -TimeoutSec 20
Write-Host ("    /workflow/simple-flow/list  code={0}  total={1}" -f $chk.code, $chk.total)
exit 0
