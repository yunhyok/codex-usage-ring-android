[CmdletBinding()]
param(
    [string] $BomPath = 'build/reports/cyclonedx/bom.json'
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

    if (-not ($licenses | Where-Object { $allowed.Contains($_) })) {
        $violations.Add("no approved license: $($component.purl) [$($licenses -join ', ')]")
    }
}

if ($violations.Count -gt 0) {
    throw "SBOM license policy failed:`n$($violations -join "`n")"
}

Write-Output "SBOM license policy passed: $($components.Count) components, $($reviewedMissing.Count) exact reviewed metadata exceptions."
