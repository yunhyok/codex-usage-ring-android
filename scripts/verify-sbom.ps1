[CmdletBinding()]
param(
    [string] $BomPath = 'build/reports/cyclonedx/bom.json',
    [switch] $RequireRustlsVerifier,
    [string] $VendorRoot = 'third_party/rustls-platform-verifier-android'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $BomPath -PathType Leaf)) {
    throw "CycloneDX SBOM is missing: $BomPath"
}

$bom = Get-Content -LiteralPath $BomPath -Raw | ConvertFrom-Json
$components = @($bom.components)
if ($bom.specVersion -ne '1.6' -or $components.Count -eq 0) {
    throw 'Expected a non-empty CycloneDX 1.6 SBOM.'
}

if ($RequireRustlsVerifier) {
    $verifier = @($components | Where-Object {
        [string]$_.name -eq 'rustls-platform-verifier-android' -or
        [string]$_.purl -like 'pkg:generic/rustls-platform-verifier-android@*'
    })
    if ($verifier.Count -ne 1) { throw 'Explicit rustls-platform-verifier-android component is required exactly once in the release SBOM.' }
    $verifierPurl = [string]$verifier[0].purl
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    $vendor = if ([IO.Path]::IsPathRooted($VendorRoot)) { $VendorRoot } else { Join-Path $repoRoot $VendorRoot }
    $manifest = Get-Content -LiteralPath (Join-Path $vendor 'PROVENANCE.json') -Raw | ConvertFrom-Json
    $expectedPurl = "pkg:generic/rustls-platform-verifier-android@$([string]$manifest.version)?upstream_commit=$([string]$manifest.upstream_commit)"
    if ($verifierPurl -ne $expectedPurl) { throw 'rustls verifier SBOM component must include the exact pinned upstream commit.' }
    $verifierHashes = @($verifier[0].hashes | Where-Object { [string]$_.alg -eq 'SHA-256' })
    if ($verifierHashes.Count -ne 0) { throw 'rustls verifier SBOM must use named source properties rather than unlabeled component hashes.' }
    foreach ($relative in @($manifest.source_files | ForEach-Object { [string]$_ })) {
        $sourcePath = Join-Path $vendor $relative
        $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
        $property = @($verifier[0].properties | Where-Object { $_.name -eq 'vendored_source_file' -and [string]$_.value -eq "$relative sha256=$sourceHash" })
        if ($property.Count -ne 1) { throw "rustls verifier SBOM is missing source binding: $relative" }
    }
    $external = @($verifier[0].externalReferences | Where-Object { $_.type -eq 'vcs' -and [string]$_.url -eq [string]$manifest.upstream_source_url })
    if ($external.Count -ne 1) { throw 'rustls verifier SBOM external reference must match the exact pinned upstream URL.' }
}

$allowed = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@(
    'Apache-2.0',
    'MIT',
    'BSD-3-Clause',
    'BSD style',
    'EPL-1.0',
    'EPL-2.0',
    'Public Domain',
    'Bouncy Castle Licence',
    'Android Software Development Kit License Agreement'
) | ForEach-Object { [void] $allowed.Add($_) }

# CycloneDX does not receive license metadata for these reviewed components.
# Both project-module identities are covered by the repository Apache-2.0
# LICENSE. The annotation
# API 1.3.2 metadata gap is tracked by exact immutable purl, never a wildcard.
$reviewedMissing = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
[void] $reviewedMissing.Add('pkg:maven/CodexUsageRing/app@unspecified?project_path=%3Aapp')
[void] $reviewedMissing.Add('pkg:maven/io.github.yunhyok/app@0.1.0?project_path=%3Aapp')
[void] $reviewedMissing.Add('pkg:maven/javax.annotation/javax.annotation-api@1.3.2?type=jar')

$violations = [System.Collections.Generic.List[string]]::new()

function Test-AllowedLicenseExpression([string] $Expression) {
    # CycloneDX may encode an SPDX expression in one licenseChoice. Evaluate
    # AND/OR instead of accepting a matching substring: an unapproved license
    # required by AND must never be hidden by an approved sibling.
    if ($allowed.Contains($Expression)) { return $true }
    $tokens = @([regex]::Matches(($Expression -replace '/', ' OR '), '\(|\)|\bAND\b|\bOR\b|\bWITH\b|[A-Za-z0-9][A-Za-z0-9.+-]*') | ForEach-Object { $_.Value })
    if ($tokens.Count -eq 0) { return $false }
    $state = @{ Index = 0; Valid = $true }
    function Peek-SbomToken { if ($state.Index -ge $tokens.Count) { return $null }; [string]$tokens[$state.Index] }
    function Take-SbomToken { $value = Peek-SbomToken; if ($null -ne $value) { [void]($state.Index++) }; $value }
    function Parse-SbomPrimary {
        if ((Peek-SbomToken) -eq '(') {
            [void](Take-SbomToken); $value = Parse-SbomOr
            if ((Take-SbomToken) -ne ')') { $state.Valid = $false }
            return $value
        }
        $id = Take-SbomToken
        if ([string]::IsNullOrWhiteSpace($id) -or $id -in @('AND','OR','WITH',')')) { $state.Valid = $false; return $false }
        $ok = $allowed.Contains($id)
        if ((Peek-SbomToken) -eq 'WITH') {
            [void](Take-SbomToken); $exception = Take-SbomToken
            $ok = $ok -and $id -eq 'Apache-2.0' -and $exception -eq 'LLVM-exception'
        }
        $ok
    }
    function Parse-SbomAnd { $value = Parse-SbomPrimary; while ((Peek-SbomToken) -eq 'AND') { [void](Take-SbomToken); $right = Parse-SbomPrimary; $value = $value -and $right }; $value }
    function Parse-SbomOr { $value = Parse-SbomAnd; while ((Peek-SbomToken) -eq 'OR') { [void](Take-SbomToken); $right = Parse-SbomAnd; $value = $value -or $right }; $value }
    $result = Parse-SbomOr
    return $state.Valid -and $state.Index -eq $tokens.Count -and $result
}

foreach ($case in @(
    @{ Expression = 'MIT OR GPL-3.0-only'; Expected = $true },
    @{ Expression = 'MIT AND BSD-3-Clause'; Expected = $true },
    @{ Expression = 'MIT AND GPL-3.0-only'; Expected = $false },
    @{ Expression = 'Apache-2.0 WITH LLVM-exception'; Expected = $true }
)) {
    if ((Test-AllowedLicenseExpression $case.Expression) -ne $case.Expected) {
        throw "internal SPDX parser check failed: $($case.Expression)"
    }
}

foreach ($component in $components) {
    $licenses = @($component.licenses | ForEach-Object {
        if ($_.license.id) { [string] $_.license.id }
        elseif ($_.license.name) { [string] $_.license.name }
        elseif ($_.expression) { [string] $_.expression }
    } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

    if ($licenses.Count -eq 0) {
        if (-not $reviewedMissing.Contains([string] $component.purl)) {
            $violations.Add("missing license metadata: $($component.purl)")
        }
        continue
    }

    if (-not ($licenses | Where-Object { Test-AllowedLicenseExpression $_ })) {
        $violations.Add("no approved license: $($component.purl) [$($licenses -join ', ')]")
    }
}

if ($violations.Count -gt 0) {
    throw "SBOM license policy failed:`n$($violations -join "`n")"
}

Write-Output "SBOM license policy passed: $($components.Count) components, $($reviewedMissing.Count) exact reviewed metadata exceptions."
