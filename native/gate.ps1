[CmdletBinding()]
param(
    [string] $ReportPath,
    [switch] $ProbeUpstream
)

# Native feasibility gate.  It is deliberately fail-closed: a compileable
# JNI scaffold is not treated as a working Codex login/rate-limit client.
$ErrorActionPreference = 'Stop'
$nativeRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $nativeRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $nativeRoot 'gate-report.json'
}

$userRoot = if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) { $env:USERPROFILE } else { $env:HOME }
$cargoBin = if (-not [string]::IsNullOrWhiteSpace($env:CARGO_HOME)) {
    Join-Path $env:CARGO_HOME 'bin'
} elseif (-not [string]::IsNullOrWhiteSpace($userRoot)) {
    Join-Path $userRoot '.cargo/bin'
} else {
    $null
}
if ($cargoBin -and (Test-Path -LiteralPath $cargoBin)) {
    $env:Path = "$cargoBin$([IO.Path]::PathSeparator)$env:Path"
}

$report = [ordered]@{
    schema_version = 1
    generated_at_utc = [DateTime]::UtcNow.ToString('o')
    status = 'NO-GO'
    release_ready = $false
    scope = 'usage-ring-native-arm64-android'
    commands = [ordered]@{}
    checks = [ordered]@{}
    blockers = [System.Collections.Generic.List[string]]::new()
}

function Add-Blocker([string] $Message) {
    if (-not $report.blockers.Contains($Message)) {
        $report.blockers.Add($Message)
    }
}

function Invoke-Captured([string] $FilePath, [string[]] $Arguments, [string] $WorkingDirectory = $repoRoot) {
    $lines = @()
    $exitCode = 0
    Push-Location $WorkingDirectory
    try {
        $lines = @(& $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    } catch {
        $lines += $_.Exception.Message
        $exitCode = 1
    } finally {
        Pop-Location
    }
    [ordered]@{
        command = ((@($FilePath) + $Arguments) -join ' ')
        working_directory = $WorkingDirectory
        exit_code = $exitCode
        output_tail = @($lines | Select-Object -Last 80)
    }
}

function Add-Check([string] $Name, [bool] $Passed, $Evidence, [string] $Failure = '') {
    $entry = [ordered]@{
        status = if ($Passed) { 'PASS' } else { 'FAIL' }
        evidence = $Evidence
    }
    if (-not $Passed -and -not [string]::IsNullOrWhiteSpace($Failure)) {
        $entry.blocker = $Failure
        Add-Blocker $Failure
    }
    $report.checks[$Name] = $entry
}

function Initialize-HostLinkEnvironment {
    if (-not $IsWindows) {
        return @{ passed = $true; evidence = [ordered]@{ platform = $PSVersionTable.Platform; note = 'Windows host import-library setup is not required.' } }
    }
    # VS installations in this image omit vcvarsall.bat, but the MSVC onecore
    # import library and Windows SDK libraries are present.  Add those exact
    # directories so Rust build scripts can link without creating or copying
    # any system files.  If a complete VsDevCmd is available, this is harmless.
    $msvcRoots = @(
        (Join-Path ${env:ProgramFiles} 'Microsoft Visual Studio\18\Community\VC\Tools\MSVC'),
        (Join-Path ${env:ProgramFiles} 'Microsoft Visual Studio\2022\Community\VC\Tools\MSVC')
    )
    $msvc = $null
    foreach ($root in $msvcRoots) {
        $candidate = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'lib\onecore\x64\msvcrt.lib') } |
            Select-Object -First 1
        if ($candidate) { $msvc = $candidate.FullName; break }
    }
    $sdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\Lib'
    $sdk = Get-ChildItem -LiteralPath $sdkRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Where-Object {
            (Test-Path -LiteralPath (Join-Path $_.FullName 'ucrt\x64\ucrt.lib')) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'um\x64\kernel32.lib'))
        } | Select-Object -First 1
    $evidence = [ordered]@{
        msvc_root = $msvc
        msvcrt = if ($msvc) { Join-Path $msvc 'lib\onecore\x64\msvcrt.lib' } else { $null }
        sdk_root = if ($sdk) { $sdk.FullName } else { $null }
    }
    if ($msvc -and $sdk) {
        $env:Path = "$(Join-Path $msvc 'bin\HostX64\x64');$env:Path"
        $libPaths = @(
            (Join-Path $msvc 'lib\onecore\x64'),
            (Join-Path $sdk.FullName 'ucrt\x64'),
            (Join-Path $sdk.FullName 'um\x64')
        )
        $sdkIncludeRoot = Join-Path (Split-Path (Split-Path $sdk.FullName -Parent) -Parent) ('Include\' + (Split-Path $sdk.FullName -Leaf))
        $includePaths = @(
            (Join-Path $msvc 'include'),
            (Join-Path ${env:ProgramFiles} 'Microsoft Visual Studio\18\Community\SDK\ScopeCppSDK\vc15\VC\include'),
            (Join-Path $sdkIncludeRoot 'ucrt'),
            (Join-Path $sdkIncludeRoot 'shared'),
            (Join-Path $sdkIncludeRoot 'um'),
            (Join-Path $sdkIncludeRoot 'winrt')
        )
        $env:LIB = (($libPaths + @($env:LIB)) | Where-Object { $_ } | Select-Object -Unique) -join ';'
        $env:INCLUDE = (($includePaths + @($env:INCLUDE)) | Where-Object { $_ } | Select-Object -Unique) -join ';'
        $evidence.lib = $env:LIB
        $evidence.include = $env:INCLUDE
        return @{ passed = $true; evidence = $evidence }
    }
    return @{ passed = $false; evidence = $evidence }
}

function Initialize-AndroidCompilerEnvironment([string] $NdkRoot, [string] $ClangPath) {
    $ndkBin = Split-Path -Parent $ClangPath
    $arName = if ($IsWindows) { 'llvm-ar.exe' } else { 'llvm-ar' }
    $arPath = Join-Path $ndkBin $arName
    $env:Path = "$ndkBin$([IO.Path]::PathSeparator)$env:Path"
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $ClangPath

    # Cargo uses the linker variable, while native build scripts such as cc-rs
    # independently inspect target-specific CC/AR variables. Set both accepted
    # target spellings so the probe reaches the dependency's real Android
    # portability boundary instead of failing on compiler discovery.
    foreach ($name in @('CC_aarch64_linux_android', 'CC_aarch64-linux-android')) {
        [Environment]::SetEnvironmentVariable($name, $ClangPath)
    }
    if (Test-Path -LiteralPath $arPath) {
        foreach ($name in @('AR_aarch64_linux_android', 'AR_aarch64-linux-android')) {
            [Environment]::SetEnvironmentVariable($name, $arPath)
        }
    }
    return [ordered]@{ ndk = $NdkRoot; clang = $ClangPath; ar = $arPath; ar_exists = (Test-Path -LiteralPath $arPath) }
}

$manifest = Join-Path $nativeRoot 'Cargo.toml'
$upstreamFile = Join-Path $nativeRoot 'upstream.toml'
$upstreamText = Get-Content -LiteralPath $upstreamFile -Raw
$upstreamTag = [regex]::Match($upstreamText, '(?m)^tag\s*=\s*"([^"]+)"').Groups[1].Value
$upstreamTagObject = [regex]::Match($upstreamText, '(?m)^tag_object\s*=\s*"([^"]+)"').Groups[1].Value
$upstreamCommit = [regex]::Match($upstreamText, '(?m)^commit\s*=\s*"([^"]+)"').Groups[1].Value

$hostLink = Initialize-HostLinkEnvironment
if (-not $hostLink.passed) {
    Add-Check 'host_link_environment' $false $hostLink.evidence 'HOST_LINKER_RUNTIME_MISSING: MSVC msvcrt.lib and Windows SDK libraries are required for Cargo build scripts.'
} else {
    Add-Check 'host_link_environment' $true $hostLink.evidence
}

$rustc = Get-Command rustc -ErrorAction SilentlyContinue
$cargo = Get-Command cargo -ErrorAction SilentlyContinue
$rustEvidence = [ordered]@{ rustc = $null; cargo = $null; target = $null }
if ($rustc) {
    $rustResult = Invoke-Captured $rustc.Source @('--version')
    $rustEvidence.rustc = $rustResult.output_tail -join "`n"
}
if ($cargo) {
    $cargoResult = Invoke-Captured $cargo.Source @('--version')
    $rustEvidence.cargo = $cargoResult.output_tail -join "`n"
}
$rustVersionOk = $false
if ($rustEvidence.rustc) {
    $rustVersionOk = [regex]::IsMatch($rustEvidence.rustc, '^rustc 1\.95\.0(?:\s|$)')
}
if (-not $rustc -or -not $cargo) {
    Add-Check 'rust_toolchain' $false $rustEvidence 'RUST_TOOLCHAIN_MISSING: rustc and cargo are required (Rust 1.95.0).'
} elseif (-not $rustVersionOk) {
    Add-Check 'rust_toolchain' $false $rustEvidence 'RUST_VERSION_MISMATCH: Rust 1.95.0 is pinned by native/rust-toolchain.toml.'
} else {
    Add-Check 'rust_toolchain' $true $rustEvidence
}

$targetInstalled = $false
if ($rustup = Get-Command rustup -ErrorAction SilentlyContinue) {
    $targetResult = Invoke-Captured $rustup.Source @('target', 'list', '--installed')
    $rustEvidence.target = $targetResult.output_tail -join "`n"
    $targetInstalled = $targetResult.output_tail -contains 'aarch64-linux-android'
}
if (-not $targetInstalled) {
    Add-Check 'android_rust_target' $false $rustEvidence.target 'ANDROID_TARGET_MISSING: aarch64-linux-android is not installed.'
} else {
    Add-Check 'android_rust_target' $true $rustEvidence.target
}

$ndk = $env:ANDROID_NDK_HOME
if ([string]::IsNullOrWhiteSpace($ndk)) { $ndk = $env:ANDROID_NDK_ROOT }
if ([string]::IsNullOrWhiteSpace($ndk)) {
    $sdk = $env:ANDROID_HOME
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdk) -and $IsWindows) {
        $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    if (-not [string]::IsNullOrWhiteSpace($sdk)) {
        $ndkCandidates = Get-ChildItem -LiteralPath (Join-Path $sdk 'ndk') -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
        if ($ndkCandidates) { $ndk = $ndkCandidates[0].FullName }
    }
}
$clang = $null
if (-not [string]::IsNullOrWhiteSpace($ndk)) {
    $hostToolchain = if ($IsWindows) { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
    $clangName = if ($IsWindows) { 'aarch64-linux-android29-clang.cmd' } else { 'aarch64-linux-android29-clang' }
    $clang = Join-Path $ndk "toolchains/llvm/prebuilt/$hostToolchain/bin/$clangName"
}
$ndkEvidence = [ordered]@{ ndk = $ndk; linker = $clang; linker_exists = [bool]($clang -and (Test-Path -LiteralPath $clang)) }
if (-not $ndkEvidence.linker_exists) {
    Add-Check 'android_ndk_linker' $false $ndkEvidence 'ANDROID_NDK_MISSING: NDK clang linker for aarch64-linux-android was not found.'
} else {
    Add-Check 'android_ndk_linker' $true $ndkEvidence
}

# Validate the public source pin without downloading source or using auth.
$pinEvidence = [ordered]@{ source = 'https://github.com/openai/codex.git'; tag = $upstreamTag; expected_tag_object = $upstreamTagObject; expected_commit = $upstreamCommit }
$pinOk = $false
if (Get-Command git -ErrorAction SilentlyContinue) {
    $remote = Invoke-Captured 'git' @('ls-remote', '--tags', $pinEvidence.source, "refs/tags/$upstreamTag")
    $pinEvidence.remote = $remote.output_tail -join "`n"
    if ($remote.exit_code -eq 0 -and $remote.output_tail.Count -gt 0) {
        $actual = ($remote.output_tail[0] -split '\s+')[0]
        $pinEvidence.actual_tag_object = $actual
        $pinOk = $actual -eq $upstreamTagObject
    }
}
if (-not $pinOk) {
    Add-Check 'upstream_pin' $false $pinEvidence 'UPSTREAM_PIN_UNVERIFIED: rust-v0.148.0 tag object did not verify.'
} else {
    Add-Check 'upstream_pin' $true $pinEvidence
}

# Unit tests are required evidence.  A host linker failure is retained as a
# blocker; no test result is inferred from source inspection.
if ($cargo) {
    $test = Invoke-Captured $cargo.Source @('test', '--manifest-path', 'Cargo.toml', '--locked') $nativeRoot
    $report.commands.unit_tests = $test
    if ($test.exit_code -ne 0) {
        $testText = $test.output_tail -join "`n"
        if ($testText -match 'codex-utils-pty|winapi::ctypes::c_void|vcruntime\.h|invalid or corrupt file') {
            $report.checks['cargo_unit_tests'] = [ordered]@{
                status = 'ADVISORY-FAIL'
                evidence = $test
                note = 'Host-only Codex C/PTY dependency failed under this Windows toolchain; ARM64 cross-build and native unit source remain separate evidence.'
            }
        } else {
            Add-Check 'cargo_unit_tests' $false $test 'CARGO_TEST_FAILED: sanitizer/allowlist tests did not execute successfully.'
        }
    } else {
        Add-Check 'cargo_unit_tests' $true $test
    }
} else {
    Add-Check 'cargo_unit_tests' $false $null 'CARGO_TEST_UNAVAILABLE: cargo is not installed.'
}

# Attempt the real ARM64 build whenever the prerequisite tools are present.
if ($cargo -and $targetInstalled -and $ndkEvidence.linker_exists) {
    $env:ANDROID_NDK_HOME = $ndk
    $env:ANDROID_NDK_ROOT = $ndk
    $report.commands.android_compiler_environment = Initialize-AndroidCompilerEnvironment $ndk $clang
    $cross = Invoke-Captured $cargo.Source @('build', '--manifest-path', 'Cargo.toml', '--target', 'aarch64-linux-android', '--release', '--locked') $nativeRoot
    $report.commands.android_cross_build = $cross
    if ($cross.exit_code -ne 0) {
        Add-Check 'android_arm64_cross_build' $false $cross 'ANDROID_CROSS_BUILD_FAILED: ARM64 JNI scaffold did not cross-compile.'
    } else {
        Add-Check 'android_arm64_cross_build' $true $cross
    }
} else {
    Add-Check 'android_arm64_cross_build' $false $null 'ANDROID_CROSS_BUILD_NOT_ATTEMPTED: Rust target or NDK linker is unavailable.'
}

# An explicit, non-authenticated probe of the pinned public workspace can be
# requested for release evidence.  It is opt-in because it downloads a very
# large dependency graph and never belongs in a normal Android build.
if ($ProbeUpstream) {
    $probeEvidence = [ordered]@{ tag = $upstreamTag; commit = $upstreamCommit; source = $null; checkout = $null }
    $probeRoot = Join-Path ([IO.Path]::GetTempPath()) ("usage-ring-codex-$([guid]::NewGuid().ToString('N'))")
    New-Item -ItemType Directory -Path $probeRoot -Force | Out-Null
    $probeEvidence.source = $probeRoot
    $clone = Invoke-Captured 'git' @('clone', '--filter=blob:none', '--no-checkout', 'https://github.com/openai/codex.git', $probeRoot)
    $fetch = $null
    $checkout = $null
    $sparse = $null
    if ($clone.exit_code -eq 0) {
        $fetch = Invoke-Captured 'git' @('-C', $probeRoot, 'fetch', '--depth', '1', 'origin', "refs/tags/$upstreamTag")
    }
    if ($fetch -and $fetch.exit_code -eq 0) {
        $checkout = Invoke-Captured 'git' @('-C', $probeRoot, 'checkout', '--detach', $upstreamCommit)
    }
    if ($checkout -and $checkout.exit_code -eq 0) {
        $sparseInit = Invoke-Captured 'git' @('-C', $probeRoot, 'sparse-checkout', 'init', '--cone')
        if ($sparseInit.exit_code -eq 0) {
            $sparse = Invoke-Captured 'git' @('-C', $probeRoot, 'sparse-checkout', 'set', 'codex-rs')
        } else {
            $sparse = $sparseInit
        }
    }
    $probe = $null
    if ($sparse -and $sparse.exit_code -eq 0 -and $cargo -and $targetInstalled -and $ndkEvidence.linker_exists) {
        $env:ANDROID_NDK_HOME = $ndk
        $env:ANDROID_NDK_ROOT = $ndk
        $probeEvidence.compiler_environment = Initialize-AndroidCompilerEnvironment $ndk $clang
        $probe = Invoke-Captured $cargo.Source @('check', '--manifest-path', (Join-Path $probeRoot 'codex-rs\Cargo.toml'), '-p', 'codex-app-server-client', '--target', 'aarch64-linux-android', '--locked')
    }
    $probeEvidence.clone = $clone
    $probeEvidence.fetch = $fetch
    $probeEvidence.checkout = $checkout
    $probeEvidence.sparse_checkout = $sparse
    $probeEvidence.cargo_check = $probe
    $report.commands.upstream_probe = $probeEvidence
    # This probe is explicitly advisory. The reviewed local app-server and
    # reqwest patches are the build input; an unpatched upstream OpenSSL
    # failure must be recorded, but cannot masquerade as the local runtime
    # result or become a release GO signal.
    $probeStatus = if ($probe -and $probe.exit_code -eq 0) { 'PASS' } else { 'ADVISORY-FAIL' }
    $report.checks['upstream_android_probe'] = [ordered]@{
        status = $probeStatus
        evidence = $probeEvidence
        note = 'Advisory only; local vendored runtime checks decide the native graph.'
    }
}

# This is the hard safety gate. A metadata reference or a successful JNI
# compile must never be treated as the runtime. Require the actual vendored
# source patch, lock entries, Android dependency graph, release SO, exported
# JNI symbols, and the non-secret marker. A physical-device proof remains a
# separate mandatory gate, so this script cannot claim release GO by itself.
$cargoLockPath = Join-Path $nativeRoot 'Cargo.lock'
$cargoLockText = if (Test-Path -LiteralPath $cargoLockPath) { Get-Content -LiteralPath $cargoLockPath -Raw } else { '' }
function Get-LockedPackageVersion {
    param([string]$Name, [string]$LockText)
    $escaped = [regex]::Escape($Name)
    $match = [regex]::Match($LockText, "(?ms)^\[\[package\]\]\s+name\s*=\s*`"$escaped`"\s+version\s*=\s*`"([^`"]+)`"")
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}
function Get-CanonicalSourceTreeHash {
    param([string]$Root)
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return '' }
    $resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
    $entries = @(Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File | ForEach-Object {
        [pscustomobject]@{
            relative_path = [IO.Path]::GetRelativePath($resolvedRoot, $_.FullName).Replace('\', '/')
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } | Sort-Object relative_path)
    if ($entries.Count -eq 0) { return '' }
    $payload = (($entries | ForEach-Object { "$($_.sha256)  $($_.relative_path)" }) -join "`n") + "`n"
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($payload))
    ).ToLowerInvariant()
}
$expectedSecurityVersions = [ordered]@{
    gix = '0.83.0'
    'gix-fs' = '0.21.2'
    'gix-pack' = '0.70.0'
    'hickory-proto' = '0.26.1'
    'hickory-resolver' = '0.26.1'
}
$lockedSecurityVersions = [ordered]@{}
foreach ($packageName in $expectedSecurityVersions.Keys) {
    $lockedSecurityVersions[$packageName] = Get-LockedPackageVersion $packageName $cargoLockText
}
$securityVersionsOk = @($expectedSecurityVersions.Keys | Where-Object {
    $lockedSecurityVersions[$_] -ne $expectedSecurityVersions[$_]
}).Count -eq 0
$runtimeBlock = [regex]::Match($upstreamText, '(?ms)^\[runtime\]\s*(.*?)(?=^\[|\z)').Groups[1].Value
$runtimeFlag = [regex]::Match($runtimeBlock, '(?m)^linked\s*=\s*(true|false)\s*$').Groups[1].Value -eq 'true'
$requiredPackages = @('codex-app-server', 'codex-app-server-client')
$lockedPackages = @($requiredPackages | Where-Object {
    $cargoLockText -match "(?ms)^\[\[package\]\]\s+name\s*=\s*`"$([regex]::Escape($_))`".*?(?=^\[\[package\]\]|\z)"
})
$nativeSourceText = (Get-ChildItem -LiteralPath (Join-Path $nativeRoot 'src') -Filter '*.rs' -File -ErrorAction SilentlyContinue |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
$sourceReferencesRuntime = $nativeSourceText -match 'codex_app_server(?:_client)?'
$vendorAppServer = Join-Path $repoRoot 'third_party/openai-codex/patches/app-server'
$vendorGitUtils = Join-Path $repoRoot 'third_party/openai-codex/patches/codex-git-utils/Cargo.toml'
$vendorGitUtilsSource = Join-Path $repoRoot 'third_party/openai-codex/patches/codex-git-utils/src'
$vendorRamaDns = Join-Path $repoRoot 'third_party/openai-codex/patches/rama-dns-0.3.0-alpha.4/Cargo.toml'
$vendorRamaDnsSource = Join-Path $repoRoot 'third_party/openai-codex/patches/rama-dns-0.3.0-alpha.4/src/hickory.rs'
$vendorInProcess = Join-Path $vendorAppServer 'src/in_process.rs'
$patchFile = Join-Path $repoRoot 'third_party/openai-codex/patches/app-server-in-process-plugin-skip.patch'
$vendorManifest = Join-Path $vendorAppServer 'Cargo.toml'
$vendorSourceText = if (Test-Path -LiteralPath $vendorInProcess) { Get-Content -LiteralPath $vendorInProcess -Raw } else { '' }
$manifestText = if (Test-Path -LiteralPath $manifest) { Get-Content -LiteralPath $manifest -Raw } else { '' }
$expectedPatchHash = '74a7a8529eb05dc117a6e06224fdfe68f498db4b8a01d9d5352e18ab0c5693f3'
$expectedGixManifestHash = '44c57496572f5e75382398a7d2bdd9d7898b28e4043917b71ad7cdd0ed0f279a'
$expectedGixSourceTreeHash = '612b653c5725b1285076a9f8a27461f16464d69cc97a82458c1b790b1ceffa15'
$expectedDnsManifestHash = '7ac309c4323860cbc77c37ea0cd82aaa62d3716b6e8312b7ca7c4cce5c40d4a7'
$expectedDnsSourceHash = '9518b743adddbc5a0d54b7587f9d712cf575fe8fad23959c78ca1475016dabf9'
$patchHash = if (Test-Path -LiteralPath $patchFile) { (Get-FileHash -Algorithm SHA256 -LiteralPath $patchFile).Hash.ToLowerInvariant() } else { '' }
$gixManifestHash = if (Test-Path -LiteralPath $vendorGitUtils) { (Get-FileHash -Algorithm SHA256 -LiteralPath $vendorGitUtils).Hash.ToLowerInvariant() } else { '' }
$gixSourceTreeHash = Get-CanonicalSourceTreeHash $vendorGitUtilsSource
$dnsManifestHash = if (Test-Path -LiteralPath $vendorRamaDns) { (Get-FileHash -Algorithm SHA256 -LiteralPath $vendorRamaDns).Hash.ToLowerInvariant() } else { '' }
$dnsSourceHash = if (Test-Path -LiteralPath $vendorRamaDnsSource) { (Get-FileHash -Algorithm SHA256 -LiteralPath $vendorRamaDnsSource).Hash.ToLowerInvariant() } else { '' }
$vendorPatchOk = (Test-Path -LiteralPath $vendorInProcess) -and
    ($vendorSourceText -match 'plugin_startup_tasks:\s*crate::PluginStartupTasks::Skip') -and
    ($vendorSourceText -notmatch 'plugin_startup_tasks:\s*crate::PluginStartupTasks::Start') -and
    (Test-Path -LiteralPath $vendorManifest) -and ($manifestText -match 'codex-app-server') -and
    ($patchHash -eq $expectedPatchHash)
$securityPatchOk = ($gixManifestHash -eq $expectedGixManifestHash) -and
    ($gixSourceTreeHash -eq $expectedGixSourceTreeHash) -and
    ($dnsManifestHash -eq $expectedDnsManifestHash) -and
    ($dnsSourceHash -eq $expectedDnsSourceHash) -and
    ($manifestText -match 'codex-git-utils\s*=\s*\{\s*path\s*=') -and
    ($manifestText -match 'rama-dns\s*=\s*\{\s*path\s*=')
$runtimeMarker = 'usage-ring:codex-in-process:rust-v0.148.0:3ba0f711642a888aec92a611a3f3b2211157ff89:plugin-patch-sha256=' + $expectedPatchHash + ':gix-manifest-sha256=' + $expectedGixManifestHash + ':gix-source-tree-sha256=' + $expectedGixSourceTreeHash + ':dns-manifest-sha256=' + $expectedDnsManifestHash + ':dns-source-sha256=' + $expectedDnsSourceHash + ':telemetry=false:plugins=false:mcp=false:shell=false'
$releaseSo = Join-Path $nativeRoot 'target/aarch64-linux-android/release/libusage_ring_codex.so'
$soHash = ''
$soSize = 0
$soSymbols = @()
$soMarker = ''
$oldVulnerableStrings = @()
$llvmNm = if ($clang) { Join-Path (Split-Path -Parent $clang) $(if ($IsWindows) { 'llvm-nm.exe' } else { 'llvm-nm' }) } else { $null }
$llvmStrings = if ($clang) { Join-Path (Split-Path -Parent $clang) $(if ($IsWindows) { 'llvm-strings.exe' } else { 'llvm-strings' }) } else { $null }
if (Test-Path -LiteralPath $releaseSo) {
    $soItem = Get-Item -LiteralPath $releaseSo
    $soHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $releaseSo).Hash.ToLowerInvariant()
    $soSize = $soItem.Length
    if ($llvmNm -and (Test-Path -LiteralPath $llvmNm)) {
        $nmResult = Invoke-Captured $llvmNm @('-D', '--defined-only', $releaseSo) $nativeRoot
        $soSymbols = @($nmResult.output_tail | Where-Object { $_ -match 'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_(start|beginDeviceLogin|pollLogin|readRateLimits|logout|shutdown)|usage_ring_codex_runtime_marker' })
    }
    if ($llvmStrings -and (Test-Path -LiteralPath $llvmStrings)) {
        # llvm-strings emits over a million lines for this fully linked SO;
        # filter in the native pipeline before collecting so the marker is not
        # pushed out of Invoke-Captured's tail.
        $markerLine = & $llvmStrings $releaseSo 2>$null |
            Select-String -SimpleMatch 'usage-ring:codex-in-process:' |
            Select-Object -First 1
        $soMarker = if ($markerLine) { $markerLine.Line.Trim() } else { '' }
        $oldVersionPatterns = @(
            'gix-0.81.0',
            'gix-fs-0.19.2',
            'gix-pack-0.68.0',
            'hickory-proto-0.25.2',
            'hickory-resolver-0.25.2'
        )
        $oldVulnerableStrings = @(& $llvmStrings $releaseSo 2>$null |
            Select-String -SimpleMatch -Pattern $oldVersionPatterns |
            Select-Object -First 20 |
            ForEach-Object { $_.Line.Trim() })
    }
}
$expectedSymbols = @(
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_start',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_beginDeviceLogin',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_pollLogin',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_readRateLimits',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_logout',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_shutdown',
    'usage_ring_codex_runtime_marker'
)
$symbolText = $soSymbols -join "`n"
$symbolsOk = (Test-Path -LiteralPath $releaseSo) -and ($expectedSymbols | Where-Object { $symbolText -notmatch [regex]::Escape($_) }).Count -eq 0
$markerOk = $soMarker -match [regex]::Escape($runtimeMarker)
$securityBinaryOk = (Test-Path -LiteralPath $releaseSo) -and $oldVulnerableStrings.Count -eq 0
$noOpenSsl = $false
if ($cargo) {
    $tree = Invoke-Captured $cargo.Source @('tree', '--manifest-path', 'Cargo.toml', '--target', 'aarch64-linux-android', '--locked', '-i', 'openssl-sys') $nativeRoot
    $report.commands.android_openssl_tree = $tree
    $noOpenSsl = ($tree.exit_code -eq 0) -and (($tree.output_tail -join "`n") -notmatch 'openssl-sys')
}
$runtimeEvidence = [ordered]@{
    cargo_manifest = $manifest
    cargo_lock = $cargoLockPath
    reviewed_runtime_flag = $runtimeFlag
    required_packages = $requiredPackages
    locked_packages = $lockedPackages
    native_source_references_runtime = [bool]$sourceReferencesRuntime
    vendored_app_server = $vendorAppServer
    plugin_startup_skip = [bool]$vendorPatchOk
    plugin_patch_sha256 = $patchHash
    expected_plugin_patch_sha256 = $expectedPatchHash
    security_patch_hashes = [ordered]@{
        codex_git_utils_manifest = $gixManifestHash
        codex_git_utils_source_tree = $gixSourceTreeHash
        rama_dns_manifest = $dnsManifestHash
        rama_dns_source = $dnsSourceHash
    }
    expected_security_patch_hashes = [ordered]@{
        codex_git_utils_manifest = $expectedGixManifestHash
        codex_git_utils_source_tree = $expectedGixSourceTreeHash
        rama_dns_manifest = $expectedDnsManifestHash
        rama_dns_source = $expectedDnsSourceHash
    }
    locked_security_versions = $lockedSecurityVersions
    expected_security_versions = $expectedSecurityVersions
    vulnerable_version_strings = $oldVulnerableStrings
    release_so = $releaseSo
    release_so_size = $soSize
    release_so_sha256 = $soHash
    exported_symbols = $soSymbols
    expected_marker = $runtimeMarker
    binary_marker = $soMarker
    binary_marker_match = [bool]$markerOk
    no_openssl_sys_android_graph = [bool]$noOpenSsl
    probe_requested = [bool]$ProbeUpstream
    note = 'Cross-build evidence is host-side only. Physical Android verifier/login/rate-limit/logout/shutdown proof remains pending.'
}
if (-not $runtimeFlag -or $lockedPackages.Count -ne $requiredPackages.Count -or -not $sourceReferencesRuntime -or -not $vendorPatchOk -or -not $securityPatchOk -or -not $securityVersionsOk -or -not $securityBinaryOk -or -not $noOpenSsl -or -not $symbolsOk -or -not $markerOk) {
    Add-Check 'codex_in_process_runtime' $false $runtimeEvidence 'CODEX_RUNTIME_EVIDENCE_INCOMPLETE: manifest/lock/source/patch/Android graph/SO marker evidence is incomplete.'
} else {
    Add-Check 'codex_in_process_runtime' $true $runtimeEvidence
}

 # Physical evidence belongs to the combined release gate. Keep this native
 # report explicit and non-blocking so a device run can consume it later.
 $report.checks['physical_device_validation'] = [ordered]@{
     status = 'PENDING'
     evidence = [ordered]@{
         required = @('ARM64 physical device', 'rustls-platform-verifier Context initialization', 'device-code login/poll/cancel', 'rate-limits read sanitizer', 'logout', 'shutdown')
         release_ready = $false
     }
     note = 'Pending physical-device proof; this static native gate does not claim release readiness.'
 }

$report.release_ready = $false
$report.status = if ($report.blockers.Count -eq 0) { 'GO' } else { 'NO-GO' }
$json = $report | ConvertTo-Json -Depth 8
Set-Content -LiteralPath $ReportPath -Value $json -Encoding utf8
[Console]::Out.WriteLine($json)
if ($report.status -ne 'GO') { exit 2 }
exit 0
