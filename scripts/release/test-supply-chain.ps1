[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$policy = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'signing-policy.json') -Raw | ConvertFrom-Json
if ([string]$policy.status -eq 'pending') {
    if (-not [string]::IsNullOrWhiteSpace([string]$policy.sha256)) { throw 'negative fixture failed: pending signing policy must not carry a digest.' }
} elseif ([string]$policy.status -eq 'approved') {
    if ([string]$policy.sha256 -notmatch '^[0-9a-fA-F]{64}$') { throw 'negative fixture failed: approved signing policy must carry an exact SHA-256 digest.' }
} else {
    throw 'negative fixture failed: signing policy must be pending or approved.'
}

# Negative JNI-export fixture: the native gate must collect all Java_* symbols
# and reject an alternate package/class export even when the six expected
# methods are present.
$gateText = Get-Content -LiteralPath (Join-Path $repoRoot 'native\gate.ps1') -Raw
if ($gateText -notmatch '\$allJavaExports\s*=.*\$allDynamicSymbols.*\$_.+\-like ''Java_\*''') {
    throw 'negative fixture failed: native gate does not collect all Java_* dynamic exports.'
}
if ($gateText -notmatch '\$allJavaExports\.Count\s*-eq\s*\$expectedJniExports\.Count') {
    throw 'negative fixture failed: native gate does not enforce the complete JNI export set.'
}
$expectedJniFixture = @(
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_start',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_beginDeviceLogin',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_pollLogin',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_readRateLimits',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_logout',
    'Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_shutdown'
)
$hostileJniFixture = @($expectedJniFixture + 'Java_com_example_OldBridge_start')
$unexpectedJniFixture = @($hostileJniFixture | Where-Object { $expectedJniFixture -notcontains $_ })
if ($unexpectedJniFixture.Count -ne 1) {
    throw 'negative fixture failed: alternate Java package export was not rejected.'
}

# Android-tool selection fixture: signing/verification must use pinned
# executable entry points, never a recursively discovered internal jar.
$ciWorkflowText = Get-Content -LiteralPath (Join-Path $repoRoot '.github\workflows\ci.yml') -Raw
$releaseWorkflowText = Get-Content -LiteralPath (Join-Path $repoRoot '.github\workflows\release.yml') -Raw
$releasePreflightText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\release\preflight.ps1') -Raw
$devicePreflightText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\device\physical-device-preflight.ps1') -Raw
$testSignedPayloadText = Get-Content -LiteralPath (Join-Path $repoRoot 'scripts\device\verify-test-signed-payload.ps1') -Raw
if ($releaseWorkflowText -match '(?i)apksigner\.jar|Get-ChildItem[^\r\n]*apksigner\*') {
    throw 'negative fixture failed: release signing can select apksigner.jar or a recursive wildcard.'
}
if ($releaseWorkflowText -notmatch 'build-tools/36\.0\.0/apksigner') {
    throw 'negative fixture failed: release signing is not pinned to build-tools 36.0.0 apksigner.'
}
if ($releasePreflightText -match '-Filter\s+[''\"](?:apksigner|apkanalyzer)\*') {
    throw 'negative fixture failed: release preflight still recursively selects Android tool wildcards.'
}
if ($devicePreflightText -match '-Filter\s+\$LeafPattern|-Recurse[^\r\n]*\$LeafPattern') {
    throw 'negative fixture failed: device preflight still recursively selects an arbitrary Android tool path.'
}
if ($testSignedPayloadText -notmatch 'SortedDictionary\[string,\s*object\].*::new' -or
    $testSignedPayloadText -notmatch '\[StringComparer\]::Ordinal' -or
    $testSignedPayloadText -notmatch 'HashSet\[string\].*::new' -or
    $testSignedPayloadText -notmatch '\$seenNames\.Add\(\$entry\.FullName\)' -or
    $testSignedPayloadText -notmatch '\$EntryName\.StartsWith\(\$prefix,\s*\[StringComparison\]::Ordinal\)' -or
    $testSignedPayloadText -notmatch '\$leaf\.IndexOf\(' -or
    $testSignedPayloadText -notmatch '\[StringComparison\]::OrdinalIgnoreCase') {
    throw 'negative fixture failed: test-signed payload verification does not implement apksig mixed-case v1 entry recognition.'
}
if ($testSignedPayloadText -match '(?i)StartsWith\(\$prefix,\s*\[StringComparison\]::OrdinalIgnoreCase\)') {
    throw 'negative fixture failed: META-INF/ prefix filtering is case-insensitive and can hide a case-distinct payload entry.'
}

# PR workflow fixture: all CI jobs must build and publish the candidate PR head,
# not the merge SHA that GitHub exposes as github.sha for pull_request events.
$candidateExpression = 'github.event.pull_request.head.sha || github.sha'
$candidateRef = 'ref: ${{ ' + $candidateExpression + ' }}'
$candidateDefinition = 'CANDIDATE_SHA: ${{ ' + $candidateExpression + ' }}'
if ($ciWorkflowText -notmatch [regex]::Escape($candidateDefinition)) {
    throw 'negative fixture failed: CI does not define the PR-head candidate SHA with push fallback.'
}
if ([regex]::Matches($ciWorkflowText, [regex]::Escape($candidateRef)).Count -ne 3) {
    throw 'negative fixture failed: all three CI checkouts must pin the PR-head candidate SHA.'
}
foreach ($artifactName in @('android-ci-${{ env.CANDIDATE_SHA }}', 'native-ci-${{ env.CANDIDATE_SHA }}')) {
    if ($ciWorkflowText -notmatch [regex]::Escape("name: $artifactName")) {
        throw "negative fixture failed: CI artifact is not keyed by CANDIDATE_SHA ($artifactName)."
    }
}
if ($ciWorkflowText -notmatch [regex]::Escape('-ExpectedSourceCommit $env:CANDIDATE_SHA')) {
    throw 'negative fixture failed: physical preflight does not use CANDIDATE_SHA.'
}
foreach ($artifactName in @('native-ci-${{ steps.evidence.outputs.candidate_commit }}', 'native-ci-${{ needs.physical-device.outputs.candidate_commit }}')) {
    if ($releaseWorkflowText -notmatch [regex]::Escape("name: $artifactName")) {
        throw "negative fixture failed: release workflow does not resolve the head-SHA native artifact ($artifactName)."
    }
}

# Raw-versus-packaged native fixture: a raw gate hash may differ from the APK
# hash only when the exact pinned NDK strip derivation is present and checked.
if ($devicePreflightText -notmatch 'Invoke-StripDerivation' -or $devicePreflightText -notmatch '--strip-unneeded' -or $devicePreflightText -notmatch 'packaged_native_library_sha256') {
    throw 'negative fixture failed: static preflight does not prove the pinned raw-to-packaged strip derivation.'
}
if ($releaseWorkflowText -notmatch 'llvm-strip' -or $releaseWorkflowText -notmatch 'EXPECTED_RAW_NATIVE_LIBRARY_SHA256' -or $releaseWorkflowText -notmatch 'EXPECTED_PACKAGED_NATIVE_LIBRARY_SHA256') {
    throw 'negative fixture failed: release workflow does not keep raw and packaged native hashes separate.'
}
if ($releaseWorkflowText -match 'EXPECTED_NATIVE_LIBRARY_SHA256') {
    throw 'negative fixture failed: release workflow still conflates raw and packaged native hashes.'
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) ('usage-ring-sbom-negative-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    [ordered]@{
        bomFormat = 'CycloneDX'
        specVersion = '1.6'
        components = @([ordered]@{
            type = 'library'
            name = 'fixture-component'
            purl = 'pkg:generic/fixture-component@1.0.0'
            licenses = @([ordered]@{ license = [ordered]@{ id = 'MIT' } })
        })
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $fixture -Encoding utf8
    $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if ([string]::IsNullOrWhiteSpace($pwsh)) { $pwsh = (Get-Command powershell -ErrorAction Stop).Source }
    # Capture the expected child failure's stdout/stderr through ProcessStartInfo
    # instead of the call operator: Windows PowerShell can promote native stderr
    # to NativeCommandError under ErrorActionPreference=Stop before exit-code
    # inspection. ArgumentList is used when available; the quoted fallback keeps
    # this compatible with Windows PowerShell's .NET Framework Process API.
    $verifyScript = Join-Path $repoRoot 'scripts\verify-sbom.ps1'
    $childArguments = @('-NoProfile', '-File', $verifyScript, '-BomPath', $fixture, '-RequireRustlsVerifier')
    $processStartInfo = [Diagnostics.ProcessStartInfo]::new()
    $processStartInfo.FileName = $pwsh
    $processStartInfo.UseShellExecute = $false
    $processStartInfo.CreateNoWindow = $true
    $processStartInfo.RedirectStandardOutput = $true
    $processStartInfo.RedirectStandardError = $true
    if ($null -ne $processStartInfo.PSObject.Properties['ArgumentList']) {
        foreach ($argument in $childArguments) { [void]$processStartInfo.ArgumentList.Add([string]$argument) }
    } else {
        $processStartInfo.Arguments = ($childArguments | ForEach-Object {
            if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
        }) -join ' '
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $processStartInfo
    [void]$process.Start()
    $negativeOutput = @($process.StandardOutput.ReadToEnd(), $process.StandardError.ReadToEnd())
    $process.WaitForExit()
    $negativeExitCode = $process.ExitCode
    $process.Dispose()
    if ($negativeExitCode -eq 0) { throw 'negative fixture failed: SBOM without explicit rustls verifier component was accepted.' }
} finally {
    if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Force }
}

# Positive verifier-SBOM fixture: both PROVENANCE source files must be bound
# by named properties, while the exact pinned upstream URL/commit is retained.
$positiveBom = Join-Path ([IO.Path]::GetTempPath()) ('usage-ring-sbom-positive-' + [guid]::NewGuid().ToString('N') + '.json')
try {
    $vendorRoot = Join-Path $repoRoot 'third_party\rustls-platform-verifier-android'
    $manifest = Get-Content -LiteralPath (Join-Path $vendorRoot 'PROVENANCE.json') -Raw | ConvertFrom-Json
    $properties = @($manifest.source_files | ForEach-Object {
        $relative = [string]$_
        $digest = (Get-FileHash -LiteralPath (Join-Path $vendorRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant()
        [ordered]@{ name = 'vendored_source_file'; value = "$relative sha256=$digest" }
    })
    [ordered]@{
        bomFormat = 'CycloneDX'
        specVersion = '1.6'
        components = @([ordered]@{
            type = 'library'
            group = 'org.rustls'
            name = 'rustls-platform-verifier-android'
            version = [string]$manifest.version
            purl = "pkg:generic/rustls-platform-verifier-android@$([string]$manifest.version)?upstream_commit=$([string]$manifest.upstream_commit)"
            properties = $properties
            licenses = @(
                [ordered]@{ license = [ordered]@{ id = 'Apache-2.0' } },
                [ordered]@{ license = [ordered]@{ id = 'MIT' } }
            )
            externalReferences = @([ordered]@{ type = 'vcs'; url = [string]$manifest.upstream_source_url })
        })
    } | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $positiveBom -Encoding utf8
    $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if ([string]::IsNullOrWhiteSpace($pwsh)) { $pwsh = (Get-Command powershell -ErrorAction Stop).Source }
    $legacy = Get-Content -LiteralPath $positiveBom -Raw | ConvertFrom-Json
    $legacy.components[0].properties = @()
    $legacy.components[0] | Add-Member -MemberType NoteProperty -Name hashes -Value @([ordered]@{ alg = 'SHA-256'; content = (($properties[0].value -split 'sha256=')[1]) })
    $legacy | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $positiveBom -Encoding utf8
    & $pwsh -NoProfile -File (Join-Path $repoRoot 'scripts\ensure-release-sbom.ps1') -BomPath $positiveBom *> $null
    if ($LASTEXITCODE -ne 0) { throw 'positive fixture failed: legacy verifier component was not canonicalized.' }
    & $pwsh -NoProfile -File (Join-Path $repoRoot 'scripts\verify-sbom.ps1') -BomPath $positiveBom -RequireRustlsVerifier *> $null
    if ($LASTEXITCODE -ne 0) { throw 'positive fixture failed: exact two-source verifier SBOM was rejected.' }
} finally {
    if (Test-Path -LiteralPath $positiveBom) { Remove-Item -LiteralPath $positiveBom -Force }
}
Write-Output 'Supply-chain negative fixtures passed.'
