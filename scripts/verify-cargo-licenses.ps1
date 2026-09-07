[CmdletBinding()]
param(
    [string] $ManifestPath = 'native/Cargo.toml'
)

$ErrorActionPreference = 'Stop'
$cargo = Get-Command cargo -ErrorAction SilentlyContinue
if ($null -eq $cargo) {
    $candidate = Join-Path $env:USERPROFILE '.cargo/bin/cargo.exe'
    if (Test-Path -LiteralPath $candidate) { $cargo = Get-Item -LiteralPath $candidate }
}
if ($null -eq $cargo) { throw 'cargo is required for native license verification.' }
$cargoPath = if ($cargo.PSObject.Properties.Name -contains 'Source') { $cargo.Source } else { $cargo.FullName }

$json = (& $cargoPath metadata --locked --format-version 1 --manifest-path $ManifestPath 2>$null) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'cargo metadata failed for the locked native dependency graph.' }
$metadata = $json | ConvertFrom-Json
$packages = @($metadata.packages)
if ($packages.Count -eq 0) { throw 'cargo metadata returned no packages.' }

$allowedIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
@(
    '0BSD',
    'Apache-2.0',
    'BSD-1-Clause',
    'BSD-2-Clause',
    'BSD-3-Clause',
    'BSL-1.0',
    'CC0-1.0',
    'CDLA-Permissive-2.0',
    'ISC',
    'MIT',
    'MIT-0',
    'MPL-2.0',
    'Unicode-3.0',
    'Unlicense',
    'Zlib'
) | ForEach-Object { [void] $allowedIds.Add($_) }

function Test-LicenseExpression([string] $Expression) {
    # A few crates still publish slash-separated legacy metadata. In this
    # context the slash represents a choice, matching their upstream license
    # files, so normalize it to SPDX OR before parsing.
    $normalized = $Expression -replace '/', ' OR '
    $tokens = @([regex]::Matches($normalized, '\(|\)|\bAND\b|\bOR\b|\bWITH\b|[A-Za-z0-9][A-Za-z0-9.+-]*') |
        ForEach-Object { $_.Value })
    if ($tokens.Count -eq 0) { return $false }
    $state = @{ Index = 0; Tokens = $tokens; Valid = $true }

    function Peek-Token {
        if ($state.Index -ge $state.Tokens.Count) { return $null }
        return [string] $state.Tokens[$state.Index]
    }
    function Take-Token {
        $value = Peek-Token
        if ($null -ne $value) { $state.Index++ }
        return $value
    }
    function Parse-Primary {
        if ((Peek-Token) -eq '(') {
            [void] (Take-Token)
            $value = Parse-Or
            if ((Take-Token) -ne ')') { $state.Valid = $false }
            return $value
        }
        $licenseId = Take-Token
        if ([string]::IsNullOrWhiteSpace($licenseId) -or $licenseId -in @('AND', 'OR', 'WITH', ')')) {
            $state.Valid = $false
            return $false
        }
        $allowed = $allowedIds.Contains($licenseId)
        if ((Peek-Token) -eq 'WITH') {
            [void] (Take-Token)
            $exception = Take-Token
            # LLVM-exception is accepted only with Apache-2.0. A future
            # exception must be reviewed explicitly rather than being treated
            # as another license identifier.
            $allowed = $allowed -and $licenseId -eq 'Apache-2.0' -and $exception -eq 'LLVM-exception'
        }
        return $allowed
    }
    function Parse-And {
        $value = Parse-Primary
        while ((Peek-Token) -eq 'AND') {
            [void] (Take-Token)
            $right = Parse-Primary
            $value = $value -and $right
        }
        return $value
    }
    function Parse-Or {
        $value = Parse-And
        while ((Peek-Token) -eq 'OR') {
            [void] (Take-Token)
            $right = Parse-And
            $value = $value -or $right
        }
        return $value
    }

    $result = Parse-Or
    return $state.Valid -and $state.Index -eq $state.Tokens.Count -and $result
}

# Fail early if a future parser edit accidentally treats a copyleft-only term
# as permitted or changes AND/OR precedence.
foreach ($case in @(
    @{ Expression = 'MIT OR GPL-2.0-only'; Expected = $true },
    @{ Expression = 'MIT AND BSD-3-Clause'; Expected = $true },
    @{ Expression = 'MIT AND GPL-2.0-only'; Expected = $false },
    @{ Expression = 'GPL-2.0-only'; Expected = $false },
    @{ Expression = 'Apache-2.0 WITH LLVM-exception'; Expected = $true },
    @{ Expression = 'MIT WITH LLVM-exception'; Expected = $false }
)) {
    if ((Test-LicenseExpression $case.Expression) -ne $case.Expected) {
        throw "internal SPDX parser check failed: $($case.Expression)"
    }
}
$violations = [System.Collections.Generic.List[string]]::new()

foreach ($package in $packages) {
    $license = [string] $package.license
    if ([string]::IsNullOrWhiteSpace($license)) {
        $violations.Add("missing license: $($package.name)@$($package.version)")
        continue
    }
    if (-not (Test-LicenseExpression $license)) {
        $violations.Add("no approved SPDX choice: $($package.name)@$($package.version) [$license]")
    }
}

if ($violations.Count -gt 0) {
    throw "Cargo license policy failed:`n$($violations -join "`n")"
}

Write-Output "Cargo license policy passed: $($packages.Count) locked packages."
