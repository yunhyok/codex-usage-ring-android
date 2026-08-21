[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Tag,
    [Parameter(Mandatory = $true)]
    [string] $EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string] $ApkPath,
    [switch] $RequireSigning
)

$ErrorActionPreference = 'Stop'

function Stop-Preflight([string] $Message) {
    throw "RELEASE PREFLIGHT FAILED: $Message"
}

try {
    if ($Tag -ne 'v0.1.0') {
        Stop-Preflight "only v0.1.0 is eligible for this prerelease gate (received '$Tag')."
    }

    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $evidenceRoot = if ([IO.Path]::IsPathRooted($EvidenceDir)) { $EvidenceDir } else { Join-Path $repoRoot $EvidenceDir }
    $nativeEvidencePath = Join-Path $evidenceRoot 'native-gate.json'
    $deviceEvidencePath = Join-Path $evidenceRoot 'physical-device.json'
    $apk = if ([IO.Path]::IsPathRooted($ApkPath)) { $ApkPath } else { Join-Path $repoRoot $ApkPath }

    foreach ($requiredPath in @($nativeEvidencePath, $deviceEvidencePath, $apk)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            Stop-Preflight "required file is missing: $requiredPath"
        }
    }

    function Read-Evidence([string] $Path, [string] $Label) {
        try {
            $value = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
        } catch {
            Stop-Preflight "$Label is not valid JSON: $Path"
        }
        if ($null -eq $value -or $value.status -ne 'pass') {
            Stop-Preflight "$Label must contain status: pass: $Path"
        }
        return $value
    }

    $nativeEvidence = Read-Evidence $nativeEvidencePath 'native-gate evidence'
    $deviceEvidence = Read-Evidence $deviceEvidencePath 'physical-device evidence'

    foreach ($field in @('commit', 'run_url')) {
        if ([string]::IsNullOrWhiteSpace([string] $nativeEvidence.$field)) {
            Stop-Preflight "native-gate evidence is missing '$field'."
        }
    }
    if ([string]$nativeEvidence.commit -notmatch '^[0-9a-fA-F]{40}$') {
        Stop-Preflight 'native-gate evidence commit must be a 40-character source commit.'
    }
    if ([string]$nativeEvidence.run_url -notmatch '^https://') {
        Stop-Preflight 'native-gate evidence run_url must be an HTTPS review URL.'
    }

    foreach ($field in @('device_model', 'android_api', 'test_date', 'reviewer', 'run_url', 'source_commit', 'apk_sha256')) {
        if ([string]::IsNullOrWhiteSpace([string] $deviceEvidence.$field)) {
            Stop-Preflight "physical-device evidence is missing '$field'."
        }
    }
    if ([string]$deviceEvidence.source_commit -notmatch '^[0-9a-fA-F]{40}$') {
        Stop-Preflight 'physical-device evidence source_commit must be a 40-character source commit.'
    }
    if ([string]$deviceEvidence.apk_sha256 -notmatch '^[0-9a-fA-F]{64}$') {
        Stop-Preflight 'physical-device evidence apk_sha256 must be a SHA-256 digest.'
    }
    if ([string]$deviceEvidence.run_url -notmatch '^https://') {
        Stop-Preflight 'physical-device evidence run_url must be an HTTPS review URL.'
    }
    if ($RequireSigning) {
        $git = Get-Command git -ErrorAction SilentlyContinue
        if ($null -eq $git) { Stop-Preflight 'git is required to bind release evidence to the checked-out source commit.' }
        $head = ((& $git.Source -C $repoRoot rev-parse HEAD 2>&1) -join "`n").Trim()
        if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-fA-F]{40}$') { Stop-Preflight 'could not resolve the checked-out source commit.' }
        if ($nativeEvidence.commit.ToLowerInvariant() -ne $head.ToLowerInvariant() -or $deviceEvidence.source_commit.ToLowerInvariant() -ne $head.ToLowerInvariant()) {
            Stop-Preflight 'release evidence is not bound to the checked-out source commit.'
        }
    }
    $requiredDeviceChecks = @(
        'install',
        'launch',
        'native_load',
        'tls_system_trust',
        'device_code_login',
        'restart_token_refresh',
        'process_recovery',
        'rate_limits_read',
        'refresh_25',
        'widget_add_resize',
        'notification_dismiss_restore',
        'offline_recovery',
        'logout_relogin',
        'reboot_recovery',
        'secret_log_scan',
        'plugin_mcp_blocked',
        'uninstall'
    )
    foreach ($field in $requiredDeviceChecks) {
        if ($deviceEvidence.$field -ne 'pass') {
            Stop-Preflight "physical-device evidence field '$field' must be pass."
        }
    }

    if ($RequireSigning) {
        foreach ($secretName in @('ANDROID_KEYSTORE_B64', 'ANDROID_KEY_ALIAS', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_PASSWORD')) {
            $secretValue = [Environment]::GetEnvironmentVariable($secretName)
            if ([string]::IsNullOrWhiteSpace($secretValue)) {
                Stop-Preflight "external signing secret $secretName is not available; refusing to create or generate a key."
            }
        }
    }

    if ([IO.Path]::GetFileName($apk) -match '(?i)debug|unsigned') {
        Stop-Preflight "debug or unsigned APK name is not eligible: $apk"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $nativeEntries = @($archive.Entries.FullName | Where-Object { $_ -match '^lib/[^/]+/.+\.so$' })
    } finally {
        $archive.Dispose()
    }
    if ($nativeEntries -notcontains 'lib/arm64-v8a/libusage_ring_codex.so') {
        Stop-Preflight 'APK does not contain lib/arm64-v8a/libusage_ring_codex.so.'
    }
    $abis = @($nativeEntries | ForEach-Object { ($_ -split '/')[1] } | Sort-Object -Unique)
    if ($abis.Count -ne 1 -or $abis[0] -ne 'arm64-v8a') {
        Stop-Preflight "APK must contain only the arm64-v8a ABI (found: $($abis -join ', '))."
    }

    $aapt = Get-Command aapt -ErrorAction SilentlyContinue
    if ($null -eq $aapt) {
        $sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_HOME')
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT') }
        if (-not [string]::IsNullOrWhiteSpace($sdkRoot) -and (Test-Path -LiteralPath $sdkRoot)) {
            $candidate = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Filter 'aapt*' -File -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^aapt(?:\.exe)?$' } |
                Sort-Object FullName -Descending | Select-Object -First 1
            if ($null -ne $candidate) { $aapt = $candidate }
        }
    }
    if ($null -eq $aapt) {
        if ($RequireSigning) { Stop-Preflight 'aapt is required to verify release manifest identity and SDK levels.' }
        Write-Warning 'aapt not found; package/version/SDK checks were not run (non-release/local mode).'
    } else {
        $aaptPath = if ($aapt.PSObject.Properties.Name -contains 'Source') { $aapt.Source } else { $aapt.FullName }
        $badging = (& $aaptPath dump badging $apk 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { Stop-Preflight 'aapt could not read APK badging.' }
        foreach ($expected in @(
            "package: name='io.github.yunhyok.usagering' versionCode='1' versionName='0.1.0'",
            "sdkVersion:'29'",
            "targetSdkVersion:'36'"
        )) {
            if (-not $badging.Contains($expected)) { Stop-Preflight "APK badging is missing expected value: $expected" }
        }
    }

    $apkAnalyzer = Get-Command apkanalyzer -ErrorAction SilentlyContinue
    if ($null -eq $apkAnalyzer) {
        $sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_HOME')
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT') }
        if (-not [string]::IsNullOrWhiteSpace($sdkRoot)) {
            $candidate = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'cmdline-tools') -Filter 'apkanalyzer*' -File -Recurse -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending | Select-Object -First 1
            if ($null -ne $candidate) { $apkAnalyzer = $candidate }
        }
    }
    if ($null -eq $apkAnalyzer) {
        if ($RequireSigning) { Stop-Preflight 'apkanalyzer is required to verify the system-trust verifier classes.' }
        Write-Warning 'apkanalyzer not found; Android verifier class check was not run (non-release/local mode).'
    } else {
        $apkAnalyzerPath = if ($apkAnalyzer.PSObject.Properties.Name -contains 'Source') { $apkAnalyzer.Source } else { $apkAnalyzer.FullName }
        $dexPackages = (& $apkAnalyzerPath dex packages --defined-only $apk 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $dexPackages -notmatch 'org\.rustls\.platformverifier\.CertificateVerifier') {
            Stop-Preflight 'APK does not contain the pinned Android system-trust verifier classes.'
        }
    }

    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "APK: $apk"
    Write-Output "SHA256: $hash"

    $apksigner = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($null -eq $apksigner) {
        $sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_HOME')
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT') }
        if (-not [string]::IsNullOrWhiteSpace($sdkRoot) -and (Test-Path -LiteralPath $sdkRoot)) {
            $candidate = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Filter 'apksigner*' -File -Recurse -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending | Select-Object -First 1
            if ($null -ne $candidate) { $apksigner = $candidate }
        }
    }
    if ($null -eq $apksigner) {
        if ($RequireSigning) {
            Stop-Preflight 'apksigner is required for a signed release verification but was not found on PATH.'
        }
        Write-Warning 'apksigner not found; signature verification was not run (non-release/local mode).'
    } else {
        $signerPath = if ($apksigner.PSObject.Properties.Name -contains 'Source') { $apksigner.Source } else { $apksigner.FullName }
        & $signerPath verify --verbose --min-sdk-version 23 $apk
        if ($LASTEXITCODE -ne 0) {
            Stop-Preflight "apksigner rejected the APK (exit code $LASTEXITCODE)."
        }
    }

    Write-Output 'RELEASE PREFLIGHT PASSED: evidence, artifact, and configured signing gate are valid.'
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
