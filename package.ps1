# TV Shell Release 打包脚本

$projectName = "TVShell"
$distDir = "build/release"
$releaseDir = "app/build/outputs/apk/release"

$version = "unknown"
$gradleFile = "app/build.gradle.kts"
if (Test-Path $gradleFile) {
    $content = Get-Content $gradleFile -Raw
    if ($content -match 'versionName\s*=\s*"([^"]+)"') {
        $version = $matches[1]
        Write-Host "检测到版本号: v$version" -ForegroundColor Cyan
    }
}

if (Test-Path ".env") {
    Get-Content ".env" | ForEach-Object {
        if ($_ -match "(.+)=(.+)") {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim().Trim("'").Trim('"')
            [System.Environment]::SetEnvironmentVariable($name, $value)
        }
    }
}

Write-Host "停止之前的 Gradle 构建进程..." -ForegroundColor Gray
.\gradlew.bat --stop

Write-Host "开始编译 Release 正式版本..." -ForegroundColor Cyan
.\gradlew.bat assembleRelease

if ($LASTEXITCODE -eq 0) {
    if (!(Test-Path $distDir)) {
        New-Item -ItemType Directory -Path $distDir | Out-Null
    }

    $copied = 0
    $abiApks = @(
        @{ Src = "app-armeabi-v7a-release.apk"; Name = "$projectName-v$version-armeabi-v7a.apk" },
        @{ Src = "app-arm64-v8a-release.apk"; Name = "$projectName-v$version-arm64-v8a.apk" },
        @{ Src = "app-x86-release.apk"; Name = "$projectName-v$version-x86.apk" },
        @{ Src = "app-x86_64-release.apk"; Name = "$projectName-v$version-x86_64.apk" }
    )

    foreach ($item in $abiApks) {
        $src = Join-Path $releaseDir $item.Src
        if (Test-Path $src) {
            $targetPath = Join-Path $distDir $item.Name
            Copy-Item $src $targetPath -Force
            Write-Host "输出: $targetPath" -ForegroundColor Yellow
            $copied++
        }
    }

    $universal = Join-Path $releaseDir "app-release.apk"
    if (($copied -eq 0) -and (Test-Path $universal)) {
        $targetPath = Join-Path $distDir "$projectName-v$version.apk"
        Copy-Item $universal $targetPath -Force
        Write-Host "输出: $targetPath" -ForegroundColor Yellow
        $copied++
    }

    if ($copied -gt 0) {
        Write-Host "`n构建成功! 32 位电视请安装 armeabi-v7a 包。" -ForegroundColor Green
    } else {
        Write-Host "`n错误: 未找到生成的 APK 文件。" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "`n编译失败，请检查上方错误日志。" -ForegroundColor Red
    exit $LASTEXITCODE
}
