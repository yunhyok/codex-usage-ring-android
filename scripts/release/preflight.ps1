[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Tag,
    [Parameter(Mandatory = $true)]
    [string] $EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string] $ApkPath,
    [string] $SigningPolicyPath = 'scripts/release/signing-policy.json',
    [switch] $RequireSigning
)

$ErrorActionPreference = 'Stop'

function Stop-Preflight([string] $Message) {
    throw "RELEASE PREFLIGHT FAILED: $Message"
}

function Find-AndroidExecutable([string] $ToolName, [string] $WindowsRelativePath, [string] $UnixRelativePath, [string[]] $AllowedNames) {
    $roots = @(
        [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT'),
        [Environment]::GetEnvironmentVariable('ANDROID_HOME'),
        $(if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } else { $null })
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
    $relativePath = if ($IsWindows) { $WindowsRelativePath } else { $UnixRelativePath }
    foreach ($root in $roots) {
        $candidate = Join-Path $root $relativePath
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $leaf = [IO.Path]::GetFileName($candidate)
            if ($leaf -in $AllowedNames -and [IO.Path]::GetExtension($candidate) -notin @('.jar', '.class')) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
    }

    # PATH fallback is deliberately restricted to the exact executable names;
    # never recurse SDK directories or accept an internal .jar/class file.
    $command = Get-Command $ToolName -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $path = if ($command.PSObject.Properties.Name -contains 'Source') { $command.Source } else { $command.FullName }
        $leaf = [IO.Path]::GetFileName($path)
        if ($leaf -in $AllowedNames -and [IO.Path]::GetExtension($path) -notin @('.jar', '.class')) {
            return $path
        }
    }
    return $null
}

function Get-ZipEntrySha256([string] $ArchivePath, [string] $EntryName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $entry = $archive.GetEntry($EntryName)
        if ($null -eq $entry) { Stop-Preflight "APK is missing $EntryName." }
        $stream = $entry.Open()
        try {
            $sha = [Security.Cryptography.SHA256]::Create()
            try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
            finally { $sha.Dispose() }
        } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
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

    foreach ($field in @('candidate_commit', 'run_url')) {
        if ([string]::IsNullOrWhiteSpace([string] $nativeEvidence.$field)) {
            Stop-Preflight "native-gate evidence is missing '$field'."
        }
    }
    if ([string]$nativeEvidence.candidate_commit -notmatch '^[0-9a-fA-F]{40}$') {
        Stop-Preflight 'native-gate evidence candidate_commit must be a 40-character source commit.'
    }
    $runPattern = '^https://github\.com/yunhyok/codex-usage-ring-android/actions/runs/[0-9]+$'
    if ([string]$nativeEvidence.run_url -notmatch $runPattern) {
        Stop-Preflight 'native-gate evidence run_url must be this repository exact Actions URL.'
    }

    foreach ($field in @('device_model', 'android_api', 'abi', 'test_date', 'reviewer', 'run_url', 'candidate_commit', 'candidate_run_url', 'source_commit', 'apk_sha256', 'native_release_apk_sha256', 'instrumentation_apk_sha256', 'raw_native_library_sha256', 'packaged_native_library_sha256', 'strip_derivation', 'tested_release_apk_sha256', 'tested_instrumentation_apk_sha256', 'test_signing_certificate_sha256', 'release_payload_derivation')) {
        if ([string]::IsNullOrWhiteSpace([string] $deviceEvidence.$field)) {
            Stop-Preflight "physical-device evidence is missing '$field'."
        }
    }
    if ([string]$deviceEvidence.android_api -notmatch '^[0-9]+$' -or [int]$deviceEvidence.android_api -lt 29) {
        Stop-Preflight 'physical-device evidence android_api must be an integer >= 29.'
    }
    if ([string]$deviceEvidence.abi -ne 'arm64-v8a') {
        Stop-Preflight "physical-device evidence abi must be exactly arm64-v8a (found '$($deviceEvidence.abi)')."
    }
    if ([string]$deviceEvidence.candidate_commit -notmatch '^[0-9a-fA-F]{40}$' -or [string]$deviceEvidence.source_commit -ine ([string]$deviceEvidence.candidate_commit)) {
        Stop-Preflight 'physical-device evidence source_commit must equal candidate_commit.'
    }
    foreach ($field in @('apk_sha256', 'native_release_apk_sha256', 'instrumentation_apk_sha256', 'raw_native_library_sha256', 'packaged_native_library_sha256', 'tested_release_apk_sha256', 'tested_instrumentation_apk_sha256', 'test_signing_certificate_sha256')) {
        if ([string]$deviceEvidence.$field -notmatch '^[0-9a-fA-F]{64}$') { Stop-Preflight "physical-device evidence $field must be a SHA-256 digest." }
    }
    if ([string]$deviceEvidence.release_payload_derivation -ne 'apksigner-sign-only') { Stop-Preflight 'physical-device evidence must attest release_payload_derivation=apksigner-sign-only.' }
    if ([string]$deviceEvidence.strip_derivation -ne 'ndk-28.2.13676358-llvm-strip-unneeded') { Stop-Preflight 'physical-device evidence must identify the pinned NDK strip derivation.' }
    $deviceRunPattern = '^https://github\.com/yunhyok/codex-usage-ring-android/actions/runs/[0-9]+$'
    if ([string]$deviceEvidence.run_url -notmatch $deviceRunPattern -or [string]$deviceEvidence.candidate_run_url -ne [string]$deviceEvidence.run_url) {
        Stop-Preflight 'physical-device evidence run_url must be this repository exact Actions run URL (without query strings).'
    }
    if ($RequireSigning) {
        $git = Get-Command git -ErrorAction SilentlyContinue
        if ($null -eq $git) { Stop-Preflight 'git is required to bind release evidence to the checked-out source commit.' }
        $head = ((& $git.Source -C $repoRoot rev-parse HEAD 2>&1) -join "`n").Trim()
        if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-fA-F]{40}$') { Stop-Preflight 'could not resolve the checked-out source commit.' }
        if ($nativeEvidence.candidate_commit.ToLowerInvariant() -ne $deviceEvidence.candidate_commit.ToLowerInvariant()) {
            Stop-Preflight 'native and physical evidence use different candidate commits.'
        }
        & $git.Source -C $repoRoot merge-base --is-ancestor $nativeEvidence.candidate_commit $head
        if ($LASTEXITCODE -ne 0) {
            Stop-Preflight 'candidate evidence commit is not an ancestor of the checked-out release source.'
        }
        $allowed = @('docs/evidence/v0.1.0/native-gate.json','docs/evidence/v0.1.0/physical-device.json')
        $changed = @(& $git.Source -C $repoRoot diff --name-only $nativeEvidence.candidate_commit $head)
        if ($LASTEXITCODE -ne 0 -or ($changed | Where-Object { $_ -and $_ -notin $allowed }).Count -gt 0) {
            Stop-Preflight 'candidate-to-release diff contains files outside the two evidence JSON files.'
        }
    }
    $requiredDeviceChecks = @(
        'install',
        'launch',
        'native_release_install',
        'native_release_instrumentation',
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
        $policy = if ([IO.Path]::IsPathRooted($SigningPolicyPath)) { $SigningPolicyPath } else { Join-Path $repoRoot $SigningPolicyPath }
        if (-not (Test-Path -LiteralPath $policy -PathType Leaf)) { Stop-Preflight "signing policy is missing: $policy" }
        try { $signingPolicy = Get-Content -LiteralPath $policy -Raw | ConvertFrom-Json } catch { Stop-Preflight 'signing policy is not valid JSON.' }
        if ([string]$signingPolicy.status -ne 'approved' -or [string]$signingPolicy.sha256 -notmatch '^[0-9a-fA-F]{64}$') { Stop-Preflight 'signing policy is not approved with an exact certificate SHA-256 digest.' }
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
    $signedNativeLibraryHash = Get-ZipEntrySha256 $apk 'lib/arm64-v8a/libusage_ring_codex.so'
    if ($signedNativeLibraryHash -ine ([string]$deviceEvidence.packaged_native_library_sha256).ToLowerInvariant()) {
        Stop-Preflight 'signed release APK packaged native library digest does not match physical-device packaged evidence.'
    }

    $aapt = Find-AndroidExecutable 'aapt' 'build-tools\36.0.0\aapt.exe' 'build-tools/36.0.0/aapt' @('aapt', 'aapt.exe', 'aapt.bat', 'aapt.cmd')
    if ([string]::IsNullOrWhiteSpace($aapt)) {
        if ($RequireSigning) { Stop-Preflight 'aapt is required to verify release manifest identity and SDK levels.' }
        Write-Warning 'aapt not found; package/version/SDK checks were not run (non-release/local mode).'
    } else {
        $badging = (& $aapt dump badging $apk 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { Stop-Preflight 'aapt could not read APK badging.' }
        foreach ($expected in @(
            "package: name='io.github.yunhyok.usagering' versionCode='1' versionName='0.1.0'",
            "sdkVersion:'29'",
            "targetSdkVersion:'36'"
        )) {
            if (-not $badging.Contains($expected)) { Stop-Preflight "APK badging is missing expected value: $expected" }
        }
    }

    $apkAnalyzer = Find-AndroidExecutable 'apkanalyzer' 'cmdline-tools\latest\bin\apkanalyzer.bat' 'cmdline-tools/latest/bin/apkanalyzer' @('apkanalyzer', 'apkanalyzer.bat', 'apkanalyzer.cmd')
    if ([string]::IsNullOrWhiteSpace($apkAnalyzer)) {
        if ($RequireSigning) { Stop-Preflight 'apkanalyzer is required to verify the system-trust verifier classes.' }
        Write-Warning 'apkanalyzer not found; Android verifier class check was not run (non-release/local mode).'
    } else {
        $dexPackages = (& $apkAnalyzer dex packages --defined-only $apk 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $dexPackages -notmatch 'org\.rustls\.platformverifier\.CertificateVerifier') {
            Stop-Preflight 'APK does not contain the pinned Android system-trust verifier classes.'
        }
    }

    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "APK: $apk"
    Write-Output "SHA256: $hash"

    $apksigner = Find-AndroidExecutable 'apksigner' 'build-tools\36.0.0\apksigner.bat' 'build-tools/36.0.0/apksigner' @('apksigner', 'apksigner.bat', 'apksigner.cmd', 'apksigner.exe')
    if ([string]::IsNullOrWhiteSpace($apksigner)) {
        if ($RequireSigning) {
            Stop-Preflight 'apksigner is required for a signed release verification but was not found on PATH.'
        }
        Write-Warning 'apksigner not found; signature verification was not run (non-release/local mode).'
    } else {
        & $apksigner verify --verbose --min-sdk-version 23 $apk
        if ($LASTEXITCODE -ne 0) {
            Stop-Preflight "apksigner rejected the APK (exit code $LASTEXITCODE)."
        }
        if ($RequireSigning) {
            $certOutput = (& $apksigner verify --print-certs $apk 2>&1) -join "`n"
            if ($LASTEXITCODE -ne 0) { Stop-Preflight 'apksigner could not print the release certificate digest.' }
            $matches = [regex]::Matches($certOutput, '(?im)certificate SHA-256 digest:\s*([0-9a-fA-F:]{64,95})')
            if ($matches.Count -ne 1) { Stop-Preflight 'apksigner must report exactly one signer certificate SHA-256 digest.' }
            $actualDigest = ($matches[0].Groups[1].Value -replace ':', '').ToLowerInvariant()
            if ($actualDigest -ne ([string]$signingPolicy.sha256).ToLowerInvariant()) { Stop-Preflight 'release certificate SHA-256 digest does not match the independently reviewed signing policy.' }
            Write-Output "Verified signing certificate digest: $actualDigest"
        }
    }

    Write-Output 'RELEASE PREFLIGHT PASSED: evidence, artifact, and configured signing gate are valid.'
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
