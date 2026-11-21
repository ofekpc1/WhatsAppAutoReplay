param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME -or -not $env:JAVA_HOME) { throw 'Set ANDROID_HOME and JAVA_HOME first.' }
Push-Location $PSScriptRoot
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:testAutomaticDebugUnitTest :app:testNoAccessibilityDebugUnitTest :app:assembleAutomaticDebug :app:assembleNoAccessibilityDebug :app:lintAutomaticDebug :app:lintNoAccessibilityDebug --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle verification failed' }
    }
    $version = [regex]::Match([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'app/build.gradle.kts')), 'versionName = "([^"]+)"').Groups[1].Value
    $out = Join-Path $PSScriptRoot "outputs/whatsapp-auto-replay-$version"
    New-Item -ItemType Directory -Path $out -Force | Out-Null
    $signer = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    $audit = @()
    foreach ($variant in @('automatic','noAccessibility')) {
        $upper = $variant.Substring(0,1).ToUpperInvariant() + $variant.Substring(1)
        $suites = @(Get-ChildItem -Path "app/build/test-results/test${upper}DebugUnitTest/TEST-*.xml" | ForEach-Object { ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite })
        $count = ($suites | ForEach-Object { [int]$_.tests } | Measure-Object -Sum).Sum
        if ($count -lt 86 -or @($suites | Where-Object { [int]$_.errors -ne 0 -or [int]$_.failures -ne 0 -or [int]$_.skipped -ne 0 }).Count) { throw "Tests failed or missing: $variant" }
        $lint = [xml](Get-Content -LiteralPath "app/build/reports/lint-results-${variant}Debug.xml" -Raw)
        if (@($lint.issues.issue | Where-Object { $_.severity -in @('Error','Fatal') }).Count) { throw "Lint failed: $variant" }
        $apks = @(Get-ChildItem -Path "app/build/outputs/apk/$variant/debug/*.apk")
        if ($apks.Count -ne 1) { throw "Expected one APK for $variant" }
        $apk = $apks[0]
        $signature = & $signer verify --verbose --print-certs $apk.FullName
        if ($LASTEXITCODE -ne 0) { throw "APK signature failed: $variant" }
        $certificate = [regex]::Match(($signature -join "`n"),'certificate SHA-256 digest: (\w+)').Groups[1].Value
        if (-not $certificate) { throw 'Missing signing certificate' }
        $suffix = if ($variant -eq 'automatic') { 'experimental' } else { 'NO-ACCESSIBILITY' }
        $name = "Whatsapp-Auto-replay-$version-$suffix.apk"
        Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $out $name) -Force
        $audit += [pscustomobject]@{ variant=$variant; version=$version; apk=$name; tests=$count; lintErrors=0; sha256=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash; certificate=$certificate; physicalDeviceTested=$false; realMetaSendTested=$false }
    }
    if ($audit[0].certificate -ne $audit[1].certificate) { throw 'Variant signatures differ' }
    $audit | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $out 'verification.json') -Encoding UTF8
    Copy-Item -LiteralPath 'README.md' -Destination $out -Force
    $audit | Format-Table variant,version,tests,lintErrors,apk -AutoSize
    Write-Output $out
} finally { Pop-Location }
