# ============================================================================
# 按序执行 SQL 编排文件（解决"mysql.exe 打不开中文文件名"的问题）
#
# 背景：本机 MySQL 客户端在 Windows 上用 ANSI 路径打开文件，遇到中文文件名会报
#       `Failed to open file '二开-xxx.sql', error: 2` —— 命令行参数与 `source` 都一样。
#       因此本脚本不把文件名交给 mysql，而是**把内容读进内存再走 stdin 管道**。
#
# 用法（空库初始化：上游基线 + 全部二开增量 + 编排自检）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run-db-sql.ps1 `
#     -Database rad_oa_b1empty -WithBaseline
#
#   只跑 2.0 B1 的两个增量（幂等，可在已有库上重跑）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run-db-sql.ps1 `
#     -Database rad_oa_b1copy -Files "二开-2.0-B1-t_template增列.sql","二开-2.0-B1-模板新增表.sql"
#
# 说明：
#   - 默认编排文件是 sql\初始化-全部.sql；脚本按顺序解析其中的 `source <文件名>` 行，
#     把被引用文件的内容**就地展开**后一次性送入 mysql —— 这样"执行顺序"只有一处真源
#     （编排文件本身），编排文件末尾的自检语句也会被执行。
#   - -WithBaseline 先执行上游基线 table.sql + data.sql（**仅空库初始化**用）。
#   - mysql 批处理模式遇到错误即停；每个文件前会打印一行进度标记，便于定位失败在哪个文件。
# ============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Database,
    [string]$SqlFile,
    [string[]]$Files,
    [switch]$WithBaseline,
    [string]$MysqlExe = 'F:\dsh\ruoyiOA\env\mysql\server\bin\mysql.exe',
    [string]$HostName = '127.0.0.1',
    [string]$User = 'root'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ⚠⚠ 必须设置 $OutputEncoding（2026-10-05 实锤修复，DEV-ENV §6.33）：
#   控制"字符串 → 原生命令 stdin"的编码是 **$OutputEncoding**，不是 [Console]::OutputEncoding
#   （后者只管"读回原生命令输出"的解码）。PowerShell 5.1 的 $OutputEncoding 默认是 ASCII，
#   于是下面 `$sb.ToString() | & mysql ...` 这一步会把**所有中文写成 `?`**：
#   实测后果 = rad_oa 的 27 行 ctms 菜单在库里 menu_name 全是四个字面问号
#   （`HEX(menu_name)` = `3F3F3F3F`），侧边栏/面包屑显示 `????`，而门禁全绿 ——
#   属于"验证手段失效导致结论不可信"那一类：SQL 执行成功、权限点也全对，只有中文被吃掉了。
#   本文件此前漏了这一行，故**用它重跑任何含中文的 SQL 都会污染数据**；现已补上。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

# `powershell -File` 不能把数组传给 [string[]] 参数（会把 "a,b" 当单个字符串），
# 因此这里兼容"逗号分隔的单字符串"写法。
if ($Files -and $Files.Count -eq 1 -and $Files[0] -like '*,*') {
    $Files = @($Files[0] -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$sqlDir = Join-Path $repoRoot 'ruoyi-vue-oa-master\sql'

if (-not (Test-Path $MysqlExe)) { throw "找不到 mysql 客户端：$MysqlExe（用 -MysqlExe 指定）" }
if (-not (Test-Path $sqlDir)) { throw "找不到 sql 目录：$sqlDir" }

function Get-SqlText {
    param([string]$Path)
    if (-not (Test-Path $Path)) { throw "找不到 SQL 文件：$Path" }
    return (Get-Content -Raw -Encoding UTF8 $Path)
}

$sb = New-Object System.Text.StringBuilder
$fileCount = 0

function Add-File {
    param([System.Text.StringBuilder]$Sb, [string]$Path, [string]$Label, [ref]$Count)
    $Count.Value++
    [void]$Sb.AppendLine("SELECT '==> [$Label]' AS ``执行进度``;")
    [void]$Sb.AppendLine((Get-SqlText -Path $Path))
}

if ($Files -and $Files.Count -gt 0) {
    # ---------- 模式 A：直接给文件清单 ----------
    foreach ($f in $Files) {
        $p = if ([System.IO.Path]::IsPathRooted($f)) { $f } else { Join-Path $sqlDir $f }
        Add-File -Sb $sb -Path $p -Label ("{0}/{1} {2}" -f ($fileCount + 1), $Files.Count, (Split-Path -Leaf $p)) -Count ([ref]$fileCount)
    }
}
else {
    # ---------- 模式 B：解析编排文件（默认 sql\初始化-全部.sql） ----------
    if (-not $SqlFile) { $SqlFile = Join-Path $sqlDir '初始化-全部.sql' }
    if (-not (Test-Path $SqlFile)) { throw "找不到编排文件：$SqlFile" }

    if ($WithBaseline) {
        foreach ($b in @('table.sql', 'data.sql')) {
            Add-File -Sb $sb -Path (Join-Path $sqlDir $b) -Label ("基线 {0}" -f $b) -Count ([ref]$fileCount)
        }
    }

    foreach ($line in (Get-Content -Encoding UTF8 $SqlFile)) {
        $t = $line.Trim()
        if ($t -match '^\s*source\s+(.+?)\s*$') {
            # 5.1 的 String.Trim 只接受 char[]（PS7 才支持多字符写法）
            $name = $Matches[1].Trim([char[]]('"', "'", '`'))
            Add-File -Sb $sb -Path (Join-Path $sqlDir $name) -Label ("{0} {1}" -f ($fileCount + 1), $name) -Count ([ref]$fileCount)
        }
        else {
            # 非 source 行（注释、末尾自检）原样保留
            [void]$sb.AppendLine($line)
        }
    }
    Write-Host ("==> 编排文件：{0}（共 {1} 个增量，基线={2}）" -f (Split-Path -Leaf $SqlFile), $fileCount, [bool]$WithBaseline)
}

if ($fileCount -eq 0 -and $sb.Length -eq 0) { throw "没有任何可执行的 SQL" }

# ---------- 一次性送入 mysql ----------
$sb.ToString() | & $MysqlExe "--host=$HostName" "--user=$User" "--database=$Database" '--default-character-set=utf8mb4' '--table'
$code = $LASTEXITCODE
if ($code -ne 0) { throw ("SQL 执行失败（exit={0}）；看最后一条 ==＞ [...] 进度标记定位文件" -f $code) }
Write-Host ("==> 执行完成：{0} 个增量，库={1}" -f $fileCount, $Database)
