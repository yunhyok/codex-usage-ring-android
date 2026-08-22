[CmdletBinding()]
param(
    [string] $BomPath = 'build/reports/cyclonedx/bom.json',
    [string] $VendorRoot = 'third_party/rustls-platform-verifier-android'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$bom = if ([IO.Path]::IsPathRooted($BomPath)) { $BomPath } else { Join-Path $repoRoot $BomPath }
$vendor = if ([IO.Path]::IsPathRooted($VendorRoot)) { $VendorRoot } else { Join-Path $repoRoot $VendorRoot }
if (-not (Test-Path -LiteralPath $bom -PathType Leaf)) { throw "SBOM is missing: $bom" }
$document = Get-Content -LiteralPath $bom -Raw | ConvertFrom-Json
if ($document.specVersion -ne '1.6') { throw 'release SBOM must use CycloneDX 1.6.' }
$manifest = Get-Content -LiteralPath (Join-Path $vendor 'PROVENANCE.json') -Raw | ConvertFrom-Json
$declaredSources = @($manifest.source_files | ForEach-Object { [string]$_ })
if ($declaredSources.Count -ne 2 -or $declaredSources -notcontains 'src/main/java/org/rustls/platformverifier/CertificateVerifier.kt' -or $declaredSources -notcontains 'src/main/java/org/rustls/platformverifier/BuildConfig.java') {
    throw 'PROVENANCE.json must declare exactly CertificateVerifier.kt and BuildConfig.java for the release verifier component.'
}
$sourceRecords = @()
foreach ($relative in $declaredSources) {
    $sourcePath = Join-Path $vendor $relative
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) { throw "vendored source is missing: $relative" }
    $sourceRecords += [pscustomobject]@{
        path = $relative
        sha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if ([string]$manifest.sha256.$relative -ne $sourceRecords[-1].sha256) { throw "PROVENANCE.json digest mismatch: $relative" }
}
$upstreamCommit = [string]$manifest.upstream_commit
if ([string]$manifest.upstream_source_url -notmatch ('^https://raw\.githubusercontent\.com/rustls/rustls-platform-verifier/' + [regex]::Escape($upstreamCommit) + '/')) {
    throw 'PROVENANCE.json upstream_source_url must embed the exact upstream_commit.'
}
$component = [pscustomobject]@{
    type = 'library'
    group = 'org.rustls'
    name = 'rustls-platform-verifier-android'
    version = [string]$manifest.version
    purl = "pkg:generic/rustls-platform-verifier-android@$([string]$manifest.version)?upstream_commit=$upstreamCommit"
    properties = @($sourceRecords | ForEach-Object { [pscustomobject]@{ name = 'vendored_source_file'; value = "$($_.path) sha256=$($_.sha256)" } })
    licenses = @(
        [pscustomobject]@{ license = [pscustomobject]@{ id = 'Apache-2.0' } },
        [pscustomobject]@{ license = [pscustomobject]@{ id = 'MIT' } }
    )
    externalReferences = @([pscustomobject]@{ type = 'vcs'; url = [string]$manifest.upstream_source_url })
}
$components = @($document.components)
$existing = $components | Where-Object { [string]$_.name -eq 'rustls-platform-verifier-android' -or [string]$_.purl -like 'pkg:generic/rustls-platform-verifier-android@*' }
if ($existing.Count -eq 0) { $components += $component }
elseif ($existing.Count -ne 1) { throw 'release SBOM contains duplicate rustls verifier components.' }
else {
    # Replace any generated legacy component with the canonical representation;
    # this removes unlabeled hashes and binds both PROVENANCE source files.
    $components = @($components | Where-Object {
        [string]$_.name -ne 'rustls-platform-verifier-android' -and
        [string]$_.purl -notlike 'pkg:generic/rustls-platform-verifier-android@*'
    })
    $components += $component
}
$document.components = @($components)
$document | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $bom -Encoding utf8
Write-Output 'Release SBOM contains the explicit rustls-platform-verifier-android component.'
