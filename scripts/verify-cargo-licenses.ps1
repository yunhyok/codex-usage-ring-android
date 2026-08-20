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
@('Apache-2.0', 'MIT', 'Unlicense', 'Unicode-3.0') | ForEach-Object { [void] $allowedIds.Add($_) }
$operators = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@('AND', 'OR', 'WITH') | ForEach-Object { [void] $operators.Add($_) }
$violations = [System.Collections.Generic.List[string]]::new()

foreach ($package in $packages) {
    $license = [string] $package.license
    if ([string]::IsNullOrWhiteSpace($license)) {
        $violations.Add("missing license: $($package.name)@$($package.version)")
        continue
    }
    $ids = @([regex]::Matches($license, '[A-Za-z0-9][A-Za-z0-9.-]*') | ForEach-Object { $_.Value } |
        Where-Object { -not $operators.Contains($_) })
    $unapproved = @($ids | Where-Object { -not $allowedIds.Contains($_) })
    if ($unapproved.Count -gt 0) {
        $violations.Add("unapproved license id: $($package.name)@$($package.version) [$license]")
    }
}

if ($violations.Count -gt 0) {
    throw "Cargo license policy failed:`n$($violations -join "`n")"
}

Write-Output "Cargo license policy passed: $($packages.Count) locked packages."
