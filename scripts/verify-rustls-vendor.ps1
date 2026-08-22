[CmdletBinding()]
param(
    [string] $VendorRoot = 'third_party/rustls-platform-verifier-android',
    [switch] $RequireApproved
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = if ([IO.Path]::IsPathRooted($VendorRoot)) { $VendorRoot } else { Join-Path $repoRoot $VendorRoot }
$manifestPath = Join-Path $root 'PROVENANCE.json'

function Fail([string] $Message) { throw "RUSTLS VENDOR VERIFICATION FAILED: $Message" }
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { Fail 'PROVENANCE.json is missing.' }
try { $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json } catch { Fail "PROVENANCE.json is not valid JSON: $($_.Exception.Message)" }
if ([string]$manifest.component -ne 'rustls-platform-verifier-android' -or [string]$manifest.version -ne '0.7.0') { Fail 'unexpected verifier component identity.' }
if ([string]$manifest.upstream_commit -notmatch '^[0-9a-f]{40}$') { Fail 'upstream_commit must be a full immutable commit.' }
if ([string]$manifest.review_status -ne 'pending' -and [string]$manifest.review_status -ne 'approved') { Fail 'review_status must be pending or approved.' }
if ($RequireApproved -and [string]$manifest.review_status -ne 'approved') { Fail 'release requires independently approved verifier provenance.' }
if ([string]$manifest.upstream_source_url -notmatch ('^https://raw\.githubusercontent\.com/rustls/rustls-platform-verifier/' + [regex]::Escape([string]$manifest.upstream_commit) + '/')) { Fail 'upstream_source_url must embed the exact pinned upstream commit.' }

foreach ($license in @('LICENSE-APACHE','LICENSE-MIT')) {
    $path = Join-Path $root $license
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "required license file is missing: $license" }
    $digest = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $declared = [string]$manifest.sha256.$license
    if ($declared -notmatch '^[0-9a-f]{64}$' -or $digest -ne $declared.ToLowerInvariant()) { Fail "license digest mismatch: $license" }
}

 $declaredSources = @($manifest.source_files | ForEach-Object { [string]$_ })
if ($declaredSources.Count -eq 0) { Fail 'PROVENANCE.json must declare every vendored source file.' }
 $actualSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Recurse -File | Where-Object { $_.Extension -in @('.kt','.java') } | ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\','/') })
if ((Compare-Object ($declaredSources | Sort-Object) ($actualSources | Sort-Object)).Count -gt 0) { Fail 'PROVENANCE.json source_files does not exactly cover the vendored Kotlin/Java sources.' }

foreach ($relative in $declaredSources) {
    $path = Join-Path $root $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "declared source file is missing: $relative" }
    $digest = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $declared = [string]$manifest.sha256.$relative
    if ($declared -notmatch '^[0-9a-f]{64}$' -or $digest -ne $declared.ToLowerInvariant()) { Fail "source digest mismatch: $relative" }
}

$readme = Join-Path $root 'README.md'
if (-not (Test-Path -LiteralPath $readme -PathType Leaf)) { Fail 'README.md provenance record is missing.' }
$readmeText = Get-Content -LiteralPath $readme -Raw
if ($readmeText -notmatch [regex]::Escape([string]$manifest.upstream_commit)) { Fail 'README.md does not cite the pinned upstream commit.' }
if ($readmeText -notmatch 'LICENSE-APACHE' -or $readmeText -notmatch 'LICENSE-MIT') { Fail 'README.md does not identify both retained license texts.' }

Write-Output "Rustls verifier provenance passed: $([string]$manifest.upstream_commit)"
