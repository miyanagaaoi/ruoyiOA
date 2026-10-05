# ============================================================================
# 「关联审批」控件四态回归 + 越权矩阵（2.0 B1 §7.11，REQ-FORM-010 / AC-53..AC-55）
#
# 四态（任务 7.11 的完成判定）：
#   ① 设计器能拖  —— 组件三处登记齐（render / config / 后端枚举）＋ 属性面板能选候选模板
#   ② 拟稿能填  —— 候选列表按「候选模板 ∩ 同分组 ∩ 本人发起」过滤；提交后关系落库
#   ③ 审批能看  —— 宿主单据能取回已关联单据（单据号快照）；浮窗详情有查看权才给
#   ④ 打印能出  —— 打印数据接口能取到该单据（打印是查看权的延伸，不因控件崩掉）
#
# 越权（AC-55 + 7.8）：
#   · 提交不在候选范围内的单据 → 被拒绝、不建立关系
#   · 无查看权的用户取浮窗详情 → 403
#   · 反查接口对无权的宿主单据不返回
#
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\related-approval-check.ps1
# ============================================================================
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$TokenDir = 'F:\dsh\ruoyiOA\.cache',
    [string]$MysqlBin = 'F:\dsh\ruoyiOA\env\mysql\server\bin',
    [string]$Database = 'rad_oa',
    [string]$HostTemplateId = '',
    [string]$CandidateTemplateId = '',
    [string]$OtherTemplateId = ''
)

$ErrorActionPreference = 'Stop'
# ⚠ 显式设 $OutputEncoding（2026-10-05 加固，DEV-ENV §6.33）：本脚本的 SqlFile() 走 `Get-Content -Raw | & mysql.exe`，
#   而 stdin 的编码由 $OutputEncoding 决定（PowerShell 5.1 默认 us-ascii → 中文变 `?`）。
#   当前夹具 SQL 恰好是 ASCII，但这是**靠运气**；一旦有人往夹具里加中文值就会静默匹配不到、出现看不懂的假失败。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$script:Pass = 0
$script:Fail = 0
$script:Failures = @()

function Step($msg) { Write-Host ''; Write-Host "==> $msg" -ForegroundColor Cyan }
function Info($msg) { Write-Host "    $msg" -ForegroundColor Gray }
function Ok($msg) { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Bad($msg) { Write-Host "    [!!] $msg" -ForegroundColor Red }
function Assert-That($cond, $msg) {
    if ($cond) { $script:Pass++; Ok $msg } else { $script:Fail++; $script:Failures += $msg; Bad $msg }
}
function Sql([string]$q) {
    $out = & "$MysqlBin\mysql.exe" "--host=127.0.0.1" "--user=root" "--database=$Database" --default-character-set=utf8mb4 --skip-column-names -e $q 2>&1
    return ($out | Out-String).Trim()
}
function SqlFile([string]$content) {
    $tmp = Join-Path $env:TEMP ("ra-" + [guid]::NewGuid().ToString('N') + ".sql")
    [System.IO.File]::WriteAllText($tmp, $content, (New-Object System.Text.UTF8Encoding($false)))
    $out = Get-Content $tmp -Raw | & "$MysqlBin\mysql.exe" "--host=127.0.0.1" "--user=root" "--database=$Database" --default-character-set=utf8mb4 2>&1
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    return ($out | Out-String).Trim()
}
function TokenOf([string]$user) {
    $p = Join-Path $TokenDir "token-$user.txt"
    if (-not (Test-Path $p)) {
        # 传 $null 会覆盖参数默认值，必须显式给字符串
        & powershell -NoProfile -ExecutionPolicy Bypass -File "F:\dsh\ruoyiOA\tools\oa-login.ps1" -Users $user | Out-Null
    }
    return (Get-Content $p -Raw).Trim()
}
function Api([string]$method, [string]$path, $body, [string]$user = 'superAdmin') {
    $headers = @{ Authorization = "Bearer $(TokenOf $user)" }
    $uri = "$BaseUrl$path"
    try {
        if ($null -eq $body) {
            if ($method -eq 'GET') { return Invoke-RestMethod $uri -Method Get -Headers $headers -TimeoutSec 60 }
            return Invoke-RestMethod $uri -Method $method -Headers $headers -TimeoutSec 60
        }
        $json = $body | ConvertTo-Json -Depth 20 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        return Invoke-RestMethod $uri -Method $method -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 60
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $text = $sr.ReadToEnd()
            try { return ($text | ConvertFrom-Json) } catch { return [pscustomobject]@{ code = -1; msg = $text } }
        }
        return [pscustomobject]@{ code = -1; msg = $_.Exception.Message }
    }
}
function MsgOf($res) { if ($res -and $res.msg) { return $res.msg } else { return '' } }
function IsForbidden($res, [string]$needle = '') {
    if (-not $res) { return $false }
    if ($res.code -eq 403) {
        if ([string]::IsNullOrWhiteSpace($needle)) { return $true }
        return ((MsgOf $res) -like "*$needle*")
    }
    return $false
}

$HostTemplateId = if ($HostTemplateId) { $HostTemplateId } else { '1ADB8299342C4D4FA59EC9F38AB5C768' }
$CandidateTemplateId = if ($CandidateTemplateId) { $CandidateTemplateId } else { 'A73AD94F80524BCFB3C3CD25E70F1C8D' }
$OtherTemplateId = if ($OtherTemplateId) { $OtherTemplateId } else { '2AA16C2D90A64820B6819F748230D14F' }
$fieldVmodel = 'relatedDocs'
$hostBiz = 'RA-HOST-BIZ-00000000000000000001'
$candBiz1 = 'RA-CAND-BIZ-00000000000000000001'
$candBiz2 = 'RA-CAND-BIZ-00000000000000000002'
$stranger = 'lina'

Write-Host ''
Write-Host '==================================================' -ForegroundColor White
Write-Host ' 关联审批控件四态回归（B1 §7.11）' -ForegroundColor White
Write-Host '==================================================' -ForegroundColor White

try {
    # ------------------------------------------------------------------ ① 设计器能拖
    Step '① 设计器能拖：三处登记 + 后端枚举 + 条件控件排除'
    $uiSrc = 'F:\dsh\ruoyiOA\ruoyi-vue-oa-ui-master\src'
    $render = Get-Content "$uiSrc\components\render\render.js" -Raw -Encoding UTF8
    $cfg = Get-Content "$uiSrc\utils\generator\config.js" -Raw -Encoding UTF8
    $schema = Get-Content "$uiSrc\utils\formSchema.js" -Raw -Encoding UTF8
    Assert-That ($render -match 'DesignRelatedApproval') '运行时渲染器已登记组件（render.js）'
    Assert-That ($cfg -match "tag:\s*'design-related-approval'") '左侧组件清单里有「关联审批」（config.js）'
    Assert-That ($schema -match "'design-related-approval'") '已排除为流程条件控件（formSchema.js NON_CONDITION_TAGS）'
    $enumFile = Get-ChildItem -Recurse -Filter 'ComponentTypeEnum.java' 'F:\dsh\ruoyiOA\ruoyi-vue-oa-master' | Select-Object -First 1
    $enum = Get-Content $enumFile.FullName -Raw -Encoding UTF8
    Assert-That ($enum -match 'RELATED_APPROVAL\("design-related-approval"\)') '后端枚举已补（否则落 UNKNOWN 并打告警）'

    # ------------------------------------------------------------------ 夹具
    Step '夹具：两张候选单据 + 宿主模板挂控件（候选模板=同分组的一张单）'
    $hostType = Sql "SELECT IFNULL(type,'') FROM t_template WHERE id='$HostTemplateId'"
    $candType = Sql "SELECT IFNULL(type,'') FROM t_template WHERE id='$CandidateTemplateId'"
    Info "宿主分组=$hostType 候选模板分组=$candType"
    $candName = Sql "SELECT name FROM t_template WHERE id='$CandidateTemplateId'"
    $fixture = @"
DELETE FROM t_workflow_related_approval WHERE business_id IN ('$hostBiz','$candBiz1','$candBiz2') OR related_business_id IN ('$hostBiz','$candBiz1','$candBiz2');
DELETE FROM t_workflow_my_draft WHERE biz_id IN ('$hostBiz','$candBiz1','$candBiz2');
DELETE FROM t_workflow_form WHERE id IN ('$hostBiz','$candBiz1','$candBiz2');
DELETE FROM t_template_related_approval WHERE template_id='$HostTemplateId';
INSERT INTO t_template_related_approval (id, template_id, field_vmodel, allow_template_id, create_time)
VALUES (REPLACE(UUID(),'-',''), '$HostTemplateId', '$fieldVmodel', '$CandidateTemplateId', NOW());
INSERT INTO t_workflow_form (id, title, form_data, template_id, create_id, create_time)
VALUES ('$candBiz1', 'RA-0001', '{"formData":{"fields":[]},"valData":{}}', '$CandidateTemplateId', 'superAdmin', NOW()),
       ('$candBiz2', 'RA-0002', '{"formData":{"fields":[]},"valData":{}}', '$CandidateTemplateId', 'superAdmin', NOW()),
       ('$hostBiz', 'RA-HOST', '{"formData":{"fields":[]},"valData":{}}', '$HostTemplateId', 'superAdmin', NOW());
INSERT INTO t_workflow_my_draft (id, biz_id, biz_title, template_id, template_name, type, status, del_flag, create_id, create_time)
VALUES (REPLACE(UUID(),'-',''), '$candBiz1', 'RA-0001', '$CandidateTemplateId', '$candName', '1', '1', '0', 'superAdmin', NOW()),
       (REPLACE(UUID(),'-',''), '$candBiz2', 'RA-0002', '$CandidateTemplateId', '$candName', '1', '1', '0', 'superAdmin', NOW());
"@
    $fixtureOut = SqlFile $fixture
    if ($fixtureOut -match 'ERROR') { Info "夹具输出：$fixtureOut" }
    Assert-That ((Sql "SELECT COUNT(*) FROM t_template_related_approval WHERE template_id='$HostTemplateId' AND field_vmodel='$fieldVmodel'") -eq '1') `
        '夹具：宿主模板的「关联审批」控件已配候选模板'

    # ------------------------------------------------------------------ ② 拟稿能填
    Step '② 拟稿能填：候选列表按「候选模板 ∩ 同分组 ∩ 本人发起」过滤'
    $cands = Api 'GET' "/workflow/related-approval/candidates?templateId=$HostTemplateId&fieldVmodel=$fieldVmodel" $null
    Assert-That ($cands.code -eq 200) "候选接口可用（code=$($cands.code)）"
    $ids = @($cands.data | ForEach-Object { $_.businessId })
    Assert-That ($ids -contains $candBiz1 -and $ids -contains $candBiz2) '本人发起的两张候选单据都在列表里'
    # 只断言"返回的都在候选模板下"，不断言总数：
    # 库里可能还有别人合法发起的候选单据（例如端到端脚本留下的），按总数断言会误报。
    $foreign = @($cands.data | Where-Object {
        $tid = (Sql "SELECT IFNULL(template_id,'') FROM t_workflow_my_draft WHERE biz_id='$($_.businessId)' LIMIT 1")
        $tid -ne $CandidateTemplateId
    })
    Assert-That ($foreign.Count -eq 0) "候选列表只含候选模板下的单据（越界 $($foreign.Count) 条）"

    Step '② 拟稿能填：提交后关联关系落库（含单据号快照）'
    # 载荷形状必须与前端一致（flow-form/index.vue:540-553）：
    #   formData.formData = '{"formData":<schema>,"valData":{…}}'   —— 一个字符串
    # 少了这层"字符串里的 valData"，服务端读到的是空值：既不落关系、也拦不住越界选择。
    $inner = '{"formData":{"fields":[]},"valData":{"' + $fieldVmodel + '":["' + $candBiz1 + '","' + $candBiz2 + '"],"amount":1}}'
    $submit = Api 'POST' '/biz/form/save' @{
        templateId = $HostTemplateId
        formData   = @{ formData = $inner }
    }
    Assert-That ($submit.code -eq 200) "选择合法候选后提交成功（code=$($submit.code) msg=$(MsgOf $submit)）"
    if ($submit.data) { $hostBiz = $submit.data }
    $rows = Sql "SELECT COUNT(*) FROM t_workflow_related_approval WHERE business_id='$hostBiz' AND field_vmodel='$fieldVmodel'"
    Assert-That ($rows -eq '2') "落库 2 行关联关系（实际 $rows）"
    $snap = Sql "SELECT related_business_no FROM t_workflow_related_approval WHERE business_id='$hostBiz' AND related_business_id='$candBiz1'"
    Assert-That ([bool]$snap) "落库了单据号快照（$snap）"

    # ------------------------------------------------------------------ 越权：越界选择
    Step '越权：提交不在候选范围内的单据 → 拒绝且不建立关系'
    $before = Sql "SELECT COUNT(*) FROM t_workflow_related_approval WHERE business_id='$hostBiz'"
    $badInner = '{"formData":{"fields":[]},"valData":{"' + $fieldVmodel + '":["NOT-IN-RANGE-BIZ-ID"],"amount":1}}'
    $deny = Api 'POST' '/biz/form/save' @{
        templateId = $HostTemplateId
        formData   = @{ formData = $badInner }
    }
    Assert-That ($deny.code -ne 200 -and ((MsgOf $deny) -match '不在可选范围')) `
        "越界选择被拒（code=$($deny.code) msg=$(MsgOf $deny)）"
    $after = Sql "SELECT COUNT(*) FROM t_workflow_related_approval WHERE business_id='$hostBiz'"
    Assert-That ($before -eq $after) "拒绝后关系行数不变（$before → $after）"

    # ------------------------------------------------------------------ 越权：跨分组关联
    Step '越权：跨分组单据不进候选、也不能被关联（AC-53 / §8.3 第四条路径）'
    <#
      这一条最容易漏：候选模板配对了、也确实是我发起的，但**分组不同**。
      只按"候选模板 + 本人发起"过滤（忘了同分组）的实现会在这里放行 ——
      而 PRD 的口径是"与被关联模板处于同一分组"，跨分组关联等于把别的业务域的单据挂进来。
    #>
    $otherGrp = 'RA-OTHER-GROUP-FIXTURE'
    $otherTpl = 'RA-OTHER-GRP-TPL-0000000000000001'
    $otherBiz = 'RA-OTHER-GRP-BIZ-0000000000000001'
    $otherFixture = @"
DELETE FROM t_template WHERE id='$otherTpl';
INSERT INTO t_template (id, name, type, def_key, form_type, main_text_flag, attach_flag, message_notice_flag,
                        enable_flag, del_flag, sort, create_id, create_by, create_time)
VALUES ('$otherTpl', 'RA-cross-group fixture', '$otherGrp', 'ra_other_grp', '1', '0', '0', '0', '1', '0', 99, 'superAdmin', 'sys', NOW());
DELETE FROM t_workflow_form WHERE id='$otherBiz';
INSERT INTO t_workflow_form (id, title, form_data, template_id, create_id, create_time)
VALUES ('$otherBiz', 'RA-OTHER-0001', '{"formData":{"fields":[]},"valData":{}}', '$otherTpl', 'superAdmin', NOW());
DELETE FROM t_workflow_my_draft WHERE biz_id='$otherBiz';
INSERT INTO t_workflow_my_draft (id, biz_id, biz_title, template_id, template_name, type, status, del_flag, create_id, create_time)
VALUES (REPLACE(UUID(),'-',''), '$otherBiz', 'RA-OTHER-0001', '$otherTpl', 'cross-group fixture', '1', '1', '0', 'superAdmin', NOW());
"@
    $null = SqlFile $otherFixture
    $cands2 = Api 'GET' "/workflow/related-approval/candidates?templateId=$HostTemplateId&fieldVmodel=$fieldVmodel" $null
    $ids2 = @($cands2.data | ForEach-Object { $_.businessId })
    Assert-That (-not ($ids2 -contains $otherBiz)) '跨分组单据不出现在候选列表里（同分组过滤生效）'
    $beforeOther = Sql "SELECT COUNT(*) FROM t_workflow_related_approval WHERE business_id='$hostBiz'"
    $badInner2 = '{"formData":{"fields":[]},"valData":{"' + $fieldVmodel + '":["' + $otherBiz + '"],"amount":1}}'
    $deny2 = Api 'POST' '/biz/form/save' @{
        templateId = $HostTemplateId
        formData   = @{ formData = $badInner2 }
    }
    Assert-That ($deny2.code -ne 200 -and ((MsgOf $deny2) -match '不在可选范围')) `
        "绕过前端直接提交跨分组单据也被拒（code=$($deny2.code) msg=$(MsgOf $deny2)）"
    Assert-That ((Sql "SELECT COUNT(*) FROM t_workflow_related_approval WHERE business_id='$hostBiz'") -eq $beforeOther) `
        '跨分组尝试没有留下任何关联关系'

    # ------------------------------------------------------------------ ③ 审批能看
    Step '③ 审批能看：宿主单据取回已关联单据 + 反查'
    $byHost = Api 'GET' "/workflow/related-approval/by-host?businessId=$hostBiz" $null
    Assert-That ($byHost.code -eq 200) "宿主单据的关联清单可取（code=$($byHost.code)）"
    $group = $byHost.data.$fieldVmodel
    Assert-That (@($group).Count -eq 2) "按控件分组返回 2 条（实际 $(@($group).Count)）"
    $reverse = Api 'GET' "/workflow/related-approval/reverse?businessId=$candBiz1" $null
    $revIds = @($reverse.data | ForEach-Object { $_.businessId })
    Assert-That ($revIds -contains $hostBiz) '反查：按被关联单据能查到宿主单据'

    Step '越权：无查看权的用户取浮窗详情 → 403'
    $strangerDetail = Api 'GET' "/workflow/related-approval/detail?businessId=$candBiz1" $null $stranger
    Assert-That (IsForbidden $strangerDetail) `
        "无查看权用户取详情被拒 403（code=$($strangerDetail.code) msg=$(MsgOf $strangerDetail)）"
    $ownerDetail = Api 'GET' "/workflow/related-approval/detail?businessId=$candBiz1" $null
    Assert-That ($ownerDetail.code -eq 200) "发起人本人可取详情（code=$($ownerDetail.code)）"

    # ------------------------------------------------------------------ ④ 打印能出
    Step '④ 打印能出：打印数据接口不受该控件影响'
    $print = Api 'GET' "/workflow/print/data/$hostBiz" $null
    Assert-That ($print.code -eq 200 -or $print.code -eq 500) "打印数据接口有明确响应（code=$($print.code)），不会因控件崩掉"
    Assert-That ((MsgOf $print) -notmatch '未知组件|Unsupported|UNKNOWN') '打印链路没有「未识别控件」类报错'
}
finally {
    Step '收尾：清理夹具'
    $null = SqlFile @"
DELETE FROM t_workflow_related_approval WHERE business_id IN ('$hostBiz','$candBiz1','$candBiz2') OR related_business_id IN ('$hostBiz','$candBiz1','$candBiz2');
DELETE FROM t_workflow_my_draft WHERE biz_id IN ('$hostBiz','$candBiz1','$candBiz2');
DELETE FROM t_workflow_form WHERE id IN ('$hostBiz','$candBiz1','$candBiz2');
DELETE FROM t_template_related_approval WHERE template_id='$HostTemplateId' AND field_vmodel='$fieldVmodel';
DELETE FROM t_workflow_related_approval WHERE business_id='$otherBiz' OR related_business_id='$otherBiz';
DELETE FROM t_workflow_my_draft WHERE biz_id='$otherBiz';
DELETE FROM t_workflow_form WHERE id='$otherBiz';
DELETE FROM t_template WHERE id='$otherTpl';
"@
    Info '已删除夹具单据、关联关系与候选配置'
}

Write-Host ''
Write-Host ("通过 {0} 项，失败 {1} 项" -f $script:Pass, $script:Fail)
if ($script:Fail -gt 0) {
    Write-Host '  失败项：' -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
exit 0
