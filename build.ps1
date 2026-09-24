$ErrorActionPreference = 'Stop'
$gradle = Get-Command gradle -ErrorAction SilentlyContinue
if (-not $gradle) { throw '未找到 Gradle。请使用 Android Studio 打开项目，或安装 Gradle 8.9+。' }
& $gradle.Source ':app:assembleRelease' '--no-daemon'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "APK: $PSScriptRoot\app\build\outputs\apk\release\app-release.apk"
