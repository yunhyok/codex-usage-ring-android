[CmdletBinding()]
param(
    [string] $ApkPath = 'app/build/outputs/apk/native/debug/app-native-debug.apk',
    [string] $OutputDir = 'app/build/reports/physical-device',
    [string] $ExpectedSourceCommit = '',
    [string] $ReleaseApkPath = '',
    [string] $RawNativeLibraryPath = '',
    [string] $NdkRoot = '',
    [string] $InstrumentationApkPath = 'app/build/outputs/apk/androidTest/native/debug/app-native-debug-androidTest.apk',
    [switch] $PrepareOnly,
    [switch] $InstallAndLaunch
)

$ErrorActionPreference = 'Stop'
$packageName = 'io.github.yunhyok.usagering'

function Stop-Gate([string] $Message) {
    throw "PHYSICAL DEVICE PREFLIGHT FAILED: $Message"
}

function Find-AndroidTool([string] $RelativePath, [string] $LeafPattern) {
    $toolName = [IO.Path]::GetFileNameWithoutExtension($LeafPattern)
    $allowedNames = @($LeafPattern, $toolName, "$toolName.exe", "$toolName.bat", "$toolName.cmd") | Select-Object -Unique
    $command = Get-Command $toolName -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $path = if ($command.PSObject.Properties.Name -contains 'Source') { $command.Source } else { $command.FullName }
        if ([IO.Path]::GetFileName($path) -in $allowedNames -and [IO.Path]::GetExtension($path) -notin @('.jar', '.class')) { return $path }
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
        if (Test-Path -LiteralPath $direct -PathType Leaf) {
            $leaf = [IO.Path]::GetFileName($direct)
            if ($leaf -in $allowedNames -and [IO.Path]::GetExtension($direct) -notin @('.jar', '.class')) {
                return (Resolve-Path -LiteralPath $direct).Path
            }
        }
    }
    return $null
}

function Invoke-Adb([string] $AdbPath, [string] $Serial, [string[]] $CommandArgs) {
    # Keep command failures diagnosable without echoing command output.  Some
    # shell commands can contain user/device data, so never include their raw
    # stdout/stderr in a gate error or report.
    $output = @(& $AdbPath -s $Serial @CommandArgs 2>$null)
    if ($LASTEXITCODE -ne 0) {
        Stop-Gate "adb command failed: $($CommandArgs -join ' ')"
    }
    return $output
}

function Read-SafeLogcatHealth([string] $AdbPath, [string] $Serial, [string] $ProcessId) {
    # Consume logcat one line at a time.  The line itself is never returned,
    # concatenated, written, or included in an error; only allowlisted health
    # markers and counts survive this function.  Keep stderr suppressed too,
    # since adb diagnostics may include device/account data.
    $state = [ordered]@{
        native_loader_success = $false
        fatal_marker_count = 0
        secret_marker_count = 0
        lines_scanned = 0
    }
    $nativeLoaderPattern = '(?i)^D/nativeloader\(\s*\d+\):\s+Load .*libusage_ring_codex\.so .*:\s*ok\s*$'
    $fatalPattern = '(?i)(FATAL EXCEPTION|UnsatisfiedLinkError|\bANR in\b)'
    # This is intentionally a marker-only check.  Never persist the matching
    # value (which could be a URL, code, token, or account identity).  Require
    # exact field names or a field/value separator for ambiguous terms:
    # ordinary text such as "account profile session" and
    # "ProfileSessionManager", "code_challenge", and "client_id" are
    # allowed; markers such as "account_id=...", "verification_url: ...",
    # "oauth_token=...", "token:...", "code_verifier=...", "session=...",
    # or an email-shaped identity are denied.
    $secretPattern = '(?i)(?:\b(?:access[_ -]?token|refresh[_ -]?token|id[_ -]?token|oauth[_ -]?token|device[_ -]?code|user[_ -]?code|code[_ -]?verifier|verification[_ -]?(?:url|uri|code)|login[_ -]?(?:url|uri|code)|challenge[_ -]?(?:url|uri|code))\b|\btoken\b\s*(?:=|:|->)\s*\S+|\b(?:api[_ -]?key|client[_ -]?secret|authorization)\b\s*(?:=|:|->)\s*\S+|\bbearer\b\s+\S+|\b(?:account[_ -]?(?:id|email|name)|(?:e[-_ ]?mail|username))\b\s*(?:=|:|->)\s*\S+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|\bprofile[_ -]?(?:id|email|name)\b\s*(?:=|:|->)\s*\S+|\b(?:cookie|session)\b\s*(?:=|:|->)\s*\S+)'
    $logcatArgs = @('-s', $Serial, 'logcat', '-d', "--pid=$ProcessId", '-v', 'brief')

    try {
        & $AdbPath @logcatArgs 2>$null | ForEach-Object {
            # This variable is scoped to one pipeline item and is discarded
            # before the next line is read; no logcat buffer is accumulated.
            $line = [string]$_
            [void]($state.lines_scanned++)
            if ($line -match $secretPattern) {
                [void]($state.secret_marker_count++)
            }
            if ($line -match $nativeLoaderPattern) {
                $state.native_loader_success = $true
            }
            if ($line -match $fatalPattern) {
                [void]($state.fatal_marker_count++)
            }
            Remove-Variable -Name line -ErrorAction SilentlyContinue
        }
    } catch {
        Stop-Gate 'safe logcat collection could not be completed.'
    }
    if ($LASTEXITCODE -ne 0) {
        Stop-Gate 'safe logcat collection failed.'
    }

    # Return only non-secret booleans/counts.  In particular, never return the
    # source lines, process id, serial, or any matched value.
    return [pscustomobject]$state
}

function Assert-ExpectedSourceCommit([string] $RepoRoot, [string] $Expected) {
    if ([string]::IsNullOrWhiteSpace($Expected)) { return }
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($null -eq $git) {
        Stop-Gate 'ExpectedSourceCommit could not be verified because git is unavailable.'
    }
    $head = ((& $git.Source -C $RepoRoot rev-parse HEAD 2>$null) -join "`n").Trim()
    if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-fA-F]{40}$') {
        Stop-Gate 'ExpectedSourceCommit could not be verified against the local checkout.'
    }
    if ($head -ine $Expected) {
        Stop-Gate 'local HEAD does not match ExpectedSourceCommit.'
    }
}

function Get-ZipEntrySha256([string] $ArchivePath, [string] $EntryName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $entry = $archive.GetEntry($EntryName)
        if ($null -eq $entry) { Stop-Gate "APK is missing $EntryName." }
        $stream = $entry.Open()
        try {
            $sha = [Security.Cryptography.SHA256]::Create()
            try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
            finally { $sha.Dispose() }
        } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
}

function Invoke-StripDerivation([string] $RawPath, [string] $NdkPath) {
    if ([string]::IsNullOrWhiteSpace($RawPath) -or [string]::IsNullOrWhiteSpace($NdkPath)) { Stop-Gate 'raw native library and pinned NDK root are required for package strip derivation.' }
    if (-not (Test-Path -LiteralPath $RawPath -PathType Leaf)) { Stop-Gate 'raw native library artifact is missing.' }
    if (-not (Test-Path -LiteralPath $NdkPath -PathType Container)) { Stop-Gate 'pinned NDK root is missing.' }
    $strip = if ($IsWindows) { Join-Path $NdkPath 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe' } else { Join-Path $NdkPath 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip' }
    if (-not (Test-Path -LiteralPath $strip -PathType Leaf) -or [IO.Path]::GetFileName($strip) -notin @('llvm-strip','llvm-strip.exe')) { Stop-Gate 'pinned NDK llvm-strip executable is missing.' }
    $rawHash = (Get-FileHash -LiteralPath $RawPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('usage-ring-strip-' + [guid]::NewGuid().ToString('N') + '.so')
    $stripFailure = $null
    try {
        Copy-Item -LiteralPath $RawPath -Destination $temp -Force -ErrorAction Stop
        & $strip --strip-unneeded $temp 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'pinned NDK llvm-strip failed.' }
        $packagedHash = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant()
    } catch {
        $stripFailure = $_
    } finally {
        $cleanupFailure = $null
        try {
            if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force -ErrorAction Stop }
            if (Test-Path -LiteralPath $temp) { throw 'strip derivation temporary copy was not deleted.' }
        } catch { $cleanupFailure = $_ }
        if ($null -ne $stripFailure -and $null -ne $cleanupFailure) { Stop-Gate 'strip derivation and temporary-copy cleanup both failed.' }
        if ($null -ne $stripFailure) { Stop-Gate 'strip derivation failed.' }
        if ($null -ne $cleanupFailure) { Stop-Gate 'strip derivation cleanup failed.' }
    }
    return [pscustomobject]@{ raw_sha256 = $rawHash; packaged_sha256 = $packagedHash; tool = 'ndk-28.2.13676358-llvm-strip-unneeded' }
}

try {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $apk = if ([IO.Path]::IsPathRooted($ApkPath)) { $ApkPath } else { Join-Path $repoRoot $ApkPath }
    $outputRoot = if ([IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $repoRoot $OutputDir }
    $releaseApk = if ([string]::IsNullOrWhiteSpace($ReleaseApkPath)) { $null } elseif ([IO.Path]::IsPathRooted($ReleaseApkPath)) { $ReleaseApkPath } else { Join-Path $repoRoot $ReleaseApkPath }
    $rawNativeLibrary = if ([string]::IsNullOrWhiteSpace($RawNativeLibraryPath)) { $null } elseif ([IO.Path]::IsPathRooted($RawNativeLibraryPath)) { $RawNativeLibraryPath } else { Join-Path $repoRoot $RawNativeLibraryPath }
    $instrumentationApk = if ([IO.Path]::IsPathRooted($InstrumentationApkPath)) { $InstrumentationApkPath } else { Join-Path $repoRoot $InstrumentationApkPath }
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { Stop-Gate "APK is missing: $apk" }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSourceCommit) -and $ExpectedSourceCommit -notmatch '^[0-9a-fA-F]{40}$') {
        Stop-Gate 'ExpectedSourceCommit must be a full 40-character Git commit.'
    }
    # Validate the source binding before creating static evidence, including
    # -PrepareOnly runs.  A supplied expectation must never be silently
    # skipped when no device phase follows.
    Assert-ExpectedSourceCommit $repoRoot $ExpectedSourceCommit
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

    $nativeLibraryHash = Get-ZipEntrySha256 $apk 'lib/arm64-v8a/libusage_ring_codex.so'
    $instrumentationHash = ''
    if (-not [string]::IsNullOrWhiteSpace($InstrumentationApkPath)) {
        if (-not (Test-Path -LiteralPath $instrumentationApk -PathType Leaf)) { Stop-Gate "instrumentation APK is missing: $instrumentationApk" }
        $instrumentationHash = (Get-FileHash -LiteralPath $instrumentationApk -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $releaseHash = ''
    $releasePackagedNativeLibraryHash = ''
    $rawNativeLibraryHash = ''
    $stripDerivation = $null
    if ($null -ne $releaseApk) {
        if (-not (Test-Path -LiteralPath $releaseApk -PathType Leaf)) { Stop-Gate "unsigned native release APK is missing: $releaseApk" }
        if ([IO.Path]::GetFileName($releaseApk) -notmatch '(?i)release.*unsigned|unsigned.*release') { Stop-Gate 'ReleaseApkPath must identify the exact unsigned nativeRelease APK.' }
        if ([string]::IsNullOrWhiteSpace($RawNativeLibraryPath) -or [string]::IsNullOrWhiteSpace($NdkRoot)) { Stop-Gate 'ReleaseApkPath requires RawNativeLibraryPath and NdkRoot for deterministic strip derivation.' }
        $releaseHash = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToLowerInvariant()
        $releasePackagedNativeLibraryHash = Get-ZipEntrySha256 $releaseApk 'lib/arm64-v8a/libusage_ring_codex.so'
        $stripDerivation = Invoke-StripDerivation $rawNativeLibrary $NdkRoot
        $rawNativeLibraryHash = $stripDerivation.raw_sha256
        if ($stripDerivation.packaged_sha256 -ne $nativeLibraryHash -or $releasePackagedNativeLibraryHash -ne $stripDerivation.packaged_sha256) { Stop-Gate 'raw native library strip derivation does not match both packaged APK native payloads.' }
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
        native_release_apk_sha256 = $releaseHash
        instrumentation_apk_sha256 = $instrumentationHash
        raw_native_library_sha256 = $rawNativeLibraryHash
        packaged_native_library_sha256 = $stripDerivation.packaged_sha256
        strip_derivation = $stripDerivation.tool
        abi = 'arm64-v8a'
        min_sdk = 29
        target_sdk = 36
        native_library = 'lib/arm64-v8a/libusage_ring_codex.so'
        system_trust_verifier = 'org.rustls.platformverifier.CertificateVerifier'
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSourceCommit)) {
        # This is the caller-supplied commit that Assert-ExpectedSourceCommit
        # verified; it is not a claim that the worktree is otherwise clean.
        $staticReport.source_commit = $ExpectedSourceCommit.ToLowerInvariant()
        $staticReport.candidate_commit = $ExpectedSourceCommit.ToLowerInvariant()
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
    $kernelQemu = (Invoke-Adb $adb $serial @('shell', 'getprop', 'ro.kernel.qemu') | Select-Object -First 1).Trim()
    $bootQemu = (Invoke-Adb $adb $serial @('shell', 'getprop', 'ro.boot.qemu') | Select-Object -First 1).Trim()
    if (-not ($api -as [int]) -or [int]$api -lt 29) { Stop-Gate "Android API 29+ is required (found '$api')." }
    if (($abiList -split ',') -notcontains 'arm64-v8a') { Stop-Gate "ARM64 is required (found '$abiList')." }
    if ($serial -like 'emulator-*' -or $kernelQemu -eq '1' -or $bootQemu -eq '1') {
        Stop-Gate 'a physical Android device is required; emulator/QEMU evidence was detected.'
    }

    $pending = [ordered]@{
        status = 'pending'
        device_model = $model
        android_api = [int]$api
        abi = 'arm64-v8a'
        test_date = [DateTimeOffset]::Now.ToString('yyyy-MM-dd')
        reviewer = ''
        run_url = ''
        candidate_commit = ''
        candidate_run_url = ''
        source_commit = ''
        apk_sha256 = $apkHash
        native_release_apk_sha256 = $releaseHash
        instrumentation_apk_sha256 = $instrumentationHash
        raw_native_library_sha256 = $rawNativeLibraryHash
        packaged_native_library_sha256 = if ($null -ne $stripDerivation) { $stripDerivation.packaged_sha256 } else { $nativeLibraryHash }
        strip_derivation = if ($null -ne $stripDerivation) { $stripDerivation.tool } else { '' }
        tested_release_apk_sha256 = ''
        tested_instrumentation_apk_sha256 = ''
        test_signing_certificate_sha256 = ''
        release_payload_derivation = ''
        install = 'pending'
        launch = 'pending'
        native_release_install = 'pending'
        native_release_instrumentation = 'pending'
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
        if ($LASTEXITCODE -eq 0 -and $head -match '^[0-9a-fA-F]{40}$') {
            if (-not [string]::IsNullOrWhiteSpace($ExpectedSourceCommit) -and $head -ne $ExpectedSourceCommit) {
                Stop-Gate 'local HEAD does not match ExpectedSourceCommit.'
            }
            $pending.source_commit = $head.ToLowerInvariant()
            $pending.candidate_commit = $head.ToLowerInvariant()
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSourceCommit) -and $pending.source_commit -ne $ExpectedSourceCommit.ToLowerInvariant()) {
        Stop-Gate 'the candidate source commit could not be verified against the local checkout.'
    }

    if ($InstallAndLaunch) {
        $installOutput = Invoke-Adb $adb $serial @('install', '-r', $apk)
        if (($installOutput -join "`n") -notmatch '(?m)^Success\s*$') { Stop-Gate 'adb install did not report Success.' }
        $pending.install = 'pass'

        Invoke-Adb $adb $serial @('shell', 'am', 'force-stop', $packageName) | Out-Null
        $launchOutput = Invoke-Adb $adb $serial @('shell', 'am', 'start', '-W', '-n', "$packageName/.MainActivity")
        if (($launchOutput -join "`n") -notmatch '(?m)^Status:\s*ok\s*$') { Stop-Gate 'MainActivity did not report Status: ok.' }
        $pending.launch = 'pass'

        $appProcessId = ''
        $nativeMapped = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while ([DateTime]::UtcNow -lt $deadline -and -not $nativeMapped) {
            Start-Sleep -Seconds 1
            $appProcessId = (Invoke-Adb $adb $serial @('shell', 'pidof', $packageName) | Select-Object -First 1).Trim()
            if ([string]::IsNullOrWhiteSpace($appProcessId)) { continue }
            $maps = Invoke-Adb $adb $serial @('shell', 'run-as', $packageName, 'cat', "/proc/$appProcessId/maps")
            $nativeMapped = (($maps -join "`n") -match 'libusage_ring_codex\.so')
        }
        if ([string]::IsNullOrWhiteSpace($appProcessId)) { Stop-Gate 'application process is not running after launch.' }

        # Modern Android can mmap an uncompressed JNI library directly from base.apk.
        # In that case /proc/<pid>/maps names base.apk rather than the contained .so,
        # so require the current process's system nativeloader success record instead.
        $logcatHealth = Read-SafeLogcatHealth $adb $serial $appProcessId
        if (-not $nativeMapped -and -not $logcatHealth.native_loader_success) {
            Stop-Gate 'the native library was neither named in the process map nor confirmed by the current process nativeloader log within 15 seconds.'
        }
        $pending.native_load = 'pass'

        if ($logcatHealth.fatal_marker_count -gt 0) {
            Stop-Gate 'app log contains a fatal health marker.'
        }
        if ($logcatHealth.secret_marker_count -gt 0) {
            Stop-Gate 'app log contains a credential or device-login field marker.'
        }
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
