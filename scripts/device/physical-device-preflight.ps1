[CmdletBinding()]
param(
    [string] $ApkPath = 'app/build/outputs/apk/native/debug/app-native-debug.apk',
    [string] $OutputDir = 'app/build/reports/physical-device',
    [switch] $PrepareOnly,
    [switch] $InstallAndLaunch
)

$ErrorActionPreference = 'Stop'
$packageName = 'io.github.yunhyok.usagering'

function Stop-Gate([string] $Message) {
    throw "PHYSICAL DEVICE PREFLIGHT FAILED: $Message"
}

function Find-AndroidTool([string] $RelativePath, [string] $LeafPattern) {
    $command = Get-Command ([IO.Path]::GetFileNameWithoutExtension($LeafPattern)) -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $(if ($command.PSObject.Properties.Name -contains 'Source') { $command.Source } else { $command.FullName })
    }

    $roots = [System.Collections.Generic.List[string]]::new()
    foreach ($candidate in @(
        [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT'),
        [Environment]::GetEnvironmentVariable('ANDROID_HOME')
    )) {
        if (-not [string]::IsNullOrWhiteSpace($candidate)) { $roots.Add($candidate) }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $roots.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk'))
    }

    foreach ($root in $roots | Select-Object -Unique) {
        $direct = Join-Path $root $RelativePath
        if (Test-Path -LiteralPath $direct -PathType Leaf) { return (Resolve-Path -LiteralPath $direct).Path }
        $match = Get-ChildItem -LiteralPath $root -Filter $LeafPattern -File -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($null -ne $match) { return $match.FullName }
    }
    return $null
}

function Invoke-Adb([string] $AdbPath, [string] $Serial, [string[]] $CommandArgs) {
    $output = @(& $AdbPath -s $Serial @CommandArgs 2>&1)
    if ($LASTEXITCODE -ne 0) {
        Stop-Gate "adb command failed: $($CommandArgs -join ' ')`n$($output -join "`n")"
    }
    return $output
}

try {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $apk = if ([IO.Path]::IsPathRooted($ApkPath)) { $ApkPath } else { Join-Path $repoRoot $ApkPath }
    $outputRoot = if ([IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $repoRoot $OutputDir }
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { Stop-Gate "APK is missing: $apk" }
    New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $nativeEntries = @($archive.Entries.FullName | Where-Object { $_ -match '^lib/[^/]+/.+\.so$' })
    } finally {
        $archive.Dispose()
    }
    if ($nativeEntries -notcontains 'lib/arm64-v8a/libusage_ring_codex.so') {
        Stop-Gate 'APK does not contain lib/arm64-v8a/libusage_ring_codex.so.'
    }
    $abis = @($nativeEntries | ForEach-Object { ($_ -split '/')[1] } | Sort-Object -Unique)
    if ($abis.Count -ne 1 -or $abis[0] -ne 'arm64-v8a') {
        Stop-Gate "native APK must contain only arm64-v8a (found: $($abis -join ', '))."
    }

    $aaptRelative = if ($IsWindows) { 'build-tools\36.0.0\aapt.exe' } else { 'build-tools/36.0.0/aapt' }
    $aaptLeaf = if ($IsWindows) { 'aapt.exe' } else { 'aapt' }
    $aapt = Find-AndroidTool $aaptRelative $aaptLeaf
    if ([string]::IsNullOrWhiteSpace($aapt)) { Stop-Gate 'aapt was not found in the Android SDK.' }
    $badging = @(& $aapt dump badging $apk 2>&1)
    if ($LASTEXITCODE -ne 0) { Stop-Gate 'aapt could not read the APK.' }
    $badgingText = $badging -join "`n"
    foreach ($expected in @(
        "package: name='$packageName'",
        "sdkVersion:'29'",
        "targetSdkVersion:'36'"
    )) {
        if (-not $badgingText.Contains($expected)) { Stop-Gate "APK badging is missing: $expected" }
    }

    $apkAnalyzerRelative = if ($IsWindows) { 'cmdline-tools\latest\bin\apkanalyzer.bat' } else { 'cmdline-tools/latest/bin/apkanalyzer' }
    $apkAnalyzerLeaf = if ($IsWindows) { 'apkanalyzer.bat' } else { 'apkanalyzer' }
    $apkAnalyzer = Find-AndroidTool $apkAnalyzerRelative $apkAnalyzerLeaf
    if ([string]::IsNullOrWhiteSpace($apkAnalyzer)) { Stop-Gate 'apkanalyzer was not found in the Android SDK.' }
    $dexPackages = @(& $apkAnalyzer dex packages --defined-only $apk 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($dexPackages -join "`n") -notmatch 'org\.rustls\.platformverifier\.CertificateVerifier') {
        Stop-Gate 'APK does not contain the pinned Android system-trust verifier classes.'
    }

    $apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $staticReport = [ordered]@{
        status = 'pass'
        generated_at = [DateTimeOffset]::UtcNow.ToString('o')
        package = $packageName
        apk_sha256 = $apkHash
        abi = 'arm64-v8a'
        min_sdk = 29
        target_sdk = 36
        native_library = 'lib/arm64-v8a/libusage_ring_codex.so'
        system_trust_verifier = 'org.rustls.platformverifier.CertificateVerifier'
    }
    $staticPath = Join-Path $outputRoot 'static-apk.json'
    $staticReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $staticPath -Encoding utf8

    if ($PrepareOnly) {
        Write-Output "Static ARM64 APK preflight passed: $staticPath"
        Write-Output 'A connected physical device was intentionally not required.'
        exit 0
    }

    $adbRelative = if ($IsWindows) { 'platform-tools\adb.exe' } else { 'platform-tools/adb' }
    $adbLeaf = if ($IsWindows) { 'adb.exe' } else { 'adb' }
    $adb = Find-AndroidTool $adbRelative $adbLeaf
    if ([string]::IsNullOrWhiteSpace($adb)) { Stop-Gate 'adb was not found in the Android SDK.' }
    $deviceLines = @(& $adb devices 2>&1)
    if ($LASTEXITCODE -ne 0) { Stop-Gate 'adb devices failed.' }
    $connected = @($deviceLines | Where-Object { $_ -match '^(\S+)\s+device(?:\s|$)' })
    if ($connected.Count -ne 1) {
        Stop-Gate "exactly one authorized Android device is required (found $($connected.Count))."
    }
    $serial = ([regex]::Match($connected[0], '^(\S+)')).Groups[1].Value

    $api = (Invoke-Adb $adb $serial @('shell', 'getprop', 'ro.build.version.sdk') | Select-Object -First 1).Trim()
    $model = (Invoke-Adb $adb $serial @('shell', 'getprop', 'ro.product.model') | Select-Object -First 1).Trim()
    $abiList = (Invoke-Adb $adb $serial @('shell', 'getprop', 'ro.product.cpu.abilist') | Select-Object -First 1).Trim()
    if (-not ($api -as [int]) -or [int]$api -lt 29) { Stop-Gate "Android API 29+ is required (found '$api')." }
    if (($abiList -split ',') -notcontains 'arm64-v8a') { Stop-Gate "ARM64 is required (found '$abiList')." }

    $pending = [ordered]@{
        status = 'pending'
        device_model = $model
        android_api = [int]$api
        test_date = [DateTimeOffset]::Now.ToString('yyyy-MM-dd')
        reviewer = ''
        run_url = ''
        source_commit = ''
        apk_sha256 = $apkHash
        install = 'pending'
        launch = 'pending'
        native_load = 'pending'
        tls_system_trust = 'pending'
        device_code_login = 'pending'
        restart_token_refresh = 'pending'
        process_recovery = 'pending'
        rate_limits_read = 'pending'
        refresh_25 = 'pending'
        widget_add_resize = 'pending'
        notification_dismiss_restore = 'pending'
        offline_recovery = 'pending'
        logout_relogin = 'pending'
        reboot_recovery = 'pending'
        secret_log_scan = 'pending'
        plugin_mcp_blocked = 'pending'
        uninstall = 'pending'
    }

    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($null -ne $git) {
        $head = ((& $git.Source -C $repoRoot rev-parse HEAD 2>$null) -join "`n").Trim()
        if ($LASTEXITCODE -eq 0 -and $head -match '^[0-9a-fA-F]{40}$') { $pending.source_commit = $head.ToLowerInvariant() }
    }

    if ($InstallAndLaunch) {
        $installOutput = Invoke-Adb $adb $serial @('install', '-r', $apk)
        if (($installOutput -join "`n") -notmatch '(?m)^Success\s*$') { Stop-Gate 'adb install did not report Success.' }
        $pending.install = 'pass'

        Invoke-Adb $adb $serial @('shell', 'am', 'force-stop', $packageName) | Out-Null
        $launchOutput = Invoke-Adb $adb $serial @('shell', 'am', 'start', '-W', '-n', "$packageName/.MainActivity")
        if (($launchOutput -join "`n") -notmatch '(?m)^Status:\s*ok\s*$') { Stop-Gate 'MainActivity did not report Status: ok.' }
        $pending.launch = 'pass'

        $pid = ''
        $nativeMapped = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while ([DateTime]::UtcNow -lt $deadline -and -not $nativeMapped) {
            Start-Sleep -Seconds 1
            $pid = (Invoke-Adb $adb $serial @('shell', 'pidof', $packageName) | Select-Object -First 1).Trim()
            if ([string]::IsNullOrWhiteSpace($pid)) { continue }
            $maps = Invoke-Adb $adb $serial @('shell', 'run-as', $packageName, 'cat', "/proc/$pid/maps")
            $nativeMapped = (($maps -join "`n") -match 'libusage_ring_codex\.so')
        }
        if ([string]::IsNullOrWhiteSpace($pid)) { Stop-Gate 'application process is not running after launch.' }
        if (-not $nativeMapped) { Stop-Gate 'the native library is packaged but not mapped into the app process within 15 seconds.' }
        $pending.native_load = 'pass'

        $logcat = Invoke-Adb $adb $serial @('logcat', '-d', "--pid=$pid", '-v', 'brief') -join "`n"
        foreach ($fatal in @('FATAL EXCEPTION', 'UnsatisfiedLinkError', 'ANR in ')) {
            if ($logcat.Contains($fatal)) { Stop-Gate "app log contains fatal marker: $fatal" }
        }
        $secretPattern = '(?i)(access[_ -]?token|refresh[_ -]?token|authorization\s*:|bearer\s+[A-Za-z0-9._-]+|api[_ -]?key|user[_ -]?code|verification[_ -]?(url|uri))'
        if ($logcat -match $secretPattern) { Stop-Gate 'app log contains a credential or device-login field marker.' }
        $pending.secret_log_scan = 'pass'
    }

    $pendingPath = Join-Path $outputRoot 'physical-device.pending.json'
    $pending | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $pendingPath -Encoding utf8
    Write-Output "Device preflight record written without a serial number: $pendingPath"
    Write-Output 'This pending record is local evidence only and cannot satisfy the release gate.'
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
