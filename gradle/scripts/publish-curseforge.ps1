# ============================================================================
# TimeBus CurseForge 发布脚本（curl 直传）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File gradle/scripts/publish-curseforge.ps1
#   powershell -ExecutionPolicy Bypass -File gradle/scripts/publish-curseforge.ps1 -Version 1.0.5
#
# 说明：
#   CurseForge 上传 API 有 Cloudflare 人机验证，Gradle 插件（CurseForgeGradle 1.1.28）
#   的 Java HTTP 客户端 UA 会被拦截（403），因此用 curl + 浏览器 UA 直传。
#   版本 ID（6756=1.12.2, 7498=Forge, 9638=Client, 9639=Server）是 CurseForge
#   Minecraft 游戏内的全局固定 ID，与项目无关。
#
# 凭证（按优先级）：
#   1. 环境变量 CURSEFORGE_TOKEN
#   2. %USERPROFILE%\.gradle\gradle.properties 的 curseforge_token
# ============================================================================

param(
    [string]$Version,
    [string]$JarPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# 版本号：默认从 gradle.properties 读取
if (-not $Version) {
    $props = Get-Content -Encoding UTF8 (Join-Path $root 'gradle.properties')
    $match = $props | Where-Object { $_ -match '^mod_version\s*=' } | Select-Object -First 1
    $Version = ($match -split '=', 2)[1].Trim()
}
if (-not $Version) { throw '无法确定版本号，请用 -Version 参数指定' }

# jar：默认 build/libs/timebus-<version>.jar
if (-not $JarPath) { $JarPath = Join-Path $root "build\libs\timebus-$Version.jar" }
if (-not (Test-Path -LiteralPath $JarPath)) { throw "找不到 jar：$JarPath（先执行 gradlew build）" }

# token
$token = $env:CURSEFORGE_TOKEN
if (-not $token) {
    $userProps = Join-Path $env:USERPROFILE '.gradle\gradle.properties'
    if (Test-Path -LiteralPath $userProps) {
        $line = Get-Content -Encoding UTF8 $userProps | Where-Object { $_ -match '^curseforge_token\s*=' } | Select-Object -First 1
        if ($line) { $token = ($line -split '=', 2)[1].Trim() }
    }
}
if (-not $token) { throw '缺少 CurseForge token（环境变量 CURSEFORGE_TOKEN 或用户级 gradle.properties 的 curseforge_token）' }

# changelog：取 CHANGELOG.md 中对应版本的 "## vX.Y.Z" 段落
$changelogLines = Get-Content -Encoding UTF8 (Join-Path $root 'CHANGELOG.md')
$start = -1; $end = $changelogLines.Count
for ($i = 0; $i -lt $changelogLines.Count; $i++) {
    if ($changelogLines[$i] -match "^## v$([regex]::Escape($Version))\s*$") { $start = $i }
    elseif ($start -ge 0 -and $changelogLines[$i] -match '^## ') { $end = $i; break }
}
$changelog = if ($start -ge 0) {
    ($changelogLines[$start..($end - 1)] -join "`n").Trim()
} else {
    "See GitHub releases for details: https://github.com/zhenzi233/TimeBus/releases"
}

# metadata（版本 ID：6756=1.12.2, 7498=Forge, 9638=Client, 9639=Server）
$meta = [ordered]@{
    changelog    = $changelog
    changelogType = 'markdown'
    displayName  = "Time Bus v$Version"
    gameVersions = @(6756, 7498, 9638, 9639)
    releaseType  = 'release'
    relations    = @{ projects = @(@{ slug = 'ae2-extended-life'; type = 'requiredDependency' }) }
}
$metaJson = $meta | ConvertTo-Json -Depth 5

$utf8 = New-Object System.Text.UTF8Encoding($false)
$metaFile = Join-Path $env:TEMP "cf_meta_$Version.json"
$hdrFile = Join-Path $env:TEMP "cf_header_$Version.txt"
$resultFile = Join-Path $env:TEMP "cf_result_$Version.json"
[System.IO.File]::WriteAllText($metaFile, $metaJson, $utf8)
[System.IO.File]::WriteAllText($hdrFile, "X-Api-Token: $token", $utf8)

$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'
$projectId = 1638678

Write-Host "上传 $JarPath 到 CurseForge 项目 $projectId (v$Version) ..."
$http = curl.exe -sS -X POST "https://legacy.curseforge.com/api/projects/$projectId/upload-file" `
    -H "@$hdrFile" -A $ua -F "metadata=<$metaFile" -F "file=@$JarPath" `
    -o $resultFile -w '%{http_code}'
$result = if (Test-Path -LiteralPath $resultFile) { Get-Content -Raw -Encoding UTF8 $resultFile } else { '' }

Remove-Item -LiteralPath $metaFile, $hdrFile, $resultFile -Force -ErrorAction SilentlyContinue

if ($http -eq '200') {
    $json = $result | ConvertFrom-Json
    Write-Host "上传成功！文件 ID：$($json.id)"
} else {
    Write-Host "上传失败 (HTTP $http)：$result" -ForegroundColor Red
    exit 1
}
