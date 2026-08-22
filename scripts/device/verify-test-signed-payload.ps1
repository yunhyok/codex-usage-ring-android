[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $UnsignedReleaseApkPath,
    [Parameter(Mandatory = $true)]
    [string] $TestSignedReleaseApkPath,
    [Parameter(Mandatory = $true)]
    [string] $UnsignedInstrumentationApkPath,
    [Parameter(Mandatory = $true)]
    [string] $TestSignedInstrumentationApkPath,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string] $ExpectedCertificateSha256,
    [string] $OutputPath = ''
)

# This helper is deliberately read-only. It does not create/use a keystore,
# invoke adb, install an APK, or rebuild/repack an artifact. It verifies the
# evidence inputs after a reviewer has used Android SDK apksigner sign only.
$ErrorActionPreference = 'Stop'

function Stop-Verification([string] $Message) {
    throw "TEST-SIGNED PAYLOAD VERIFICATION FAILED: $Message"
}

function Resolve-AndroidTool([string] $LeafName, [string] $RelativePath) {
    $command = Get-Command $LeafName -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $(if ($command.PSObject.Properties.Name -contains 'Source') { $command.Source } else { $command.FullName })
    }
    foreach ($root in @(
        [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT'),
        [Environment]::GetEnvironmentVariable('ANDROID_HOME'),
        $(if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } else { $null })
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique) {
        $candidate = Join-Path $root $RelativePath
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Get-CertificateDigest([string] $Apksigner, [string] $ApkPath) {
    $output = (& $Apksigner verify --print-certs $ApkPath 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0) { Stop-Verification 'apksigner rejected a test-signed APK.' }
    $matches = [regex]::Matches($output, '(?im)certificate SHA-256 digest:\s*([0-9a-fA-F:]{64,95})')
    if ($matches.Count -ne 1) {
        Stop-Verification 'apksigner must report exactly one signer certificate SHA-256 digest.'
    }
    return ($matches[0].Groups[1].Value -replace ':', '').ToLowerInvariant()
}

function Test-V1SignatureEntry([string] $EntryName) {
    # Match Android apksig's v1 JarEntry recognition: the META-INF/ directory
    # prefix is ordinal/case-sensitive, while the direct-child artifact name
    # is ordinal/case-insensitive. Lower-case meta-inf/ and subdirectories are
    # payload entries and remain covered by the comparison.
    $prefix = 'META-INF/'
    if (-not $EntryName.StartsWith($prefix, [StringComparison]::Ordinal)) { return $false }
    $leaf = $EntryName.Substring($prefix.Length)
    if ([string]::IsNullOrEmpty($leaf) -or $leaf.IndexOf('/', [StringComparison]::Ordinal) -ge 0) { return $false }
    if ($leaf.Equals('MANIFEST.MF', [StringComparison]::OrdinalIgnoreCase) -or
        $leaf.StartsWith('SIG-', [StringComparison]::OrdinalIgnoreCase)) { return $true }
    foreach ($extension in @('.SF', '.RSA', '.DSA', '.EC')) {
        if ($leaf.EndsWith($extension, [StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $false
}

function Get-ZipPayload([string] $Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        # ZIP/APK entry names are ordinal and case-sensitive. PowerShell's
        # ordinary and ordered dictionaries are case-insensitive, which would
        # incorrectly conflate valid aapt names such as res/x.xml and
        # res/X.xml. Keep exact duplicate names fail-closed while treating
        # case-distinct entries as separate signed payloads.
        $payload = [System.Collections.Generic.SortedDictionary[string, object]]::new(
            [StringComparer]::Ordinal
        )
        $seenNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $archive.Entries) {
            # Check duplicates before filtering signature artifacts: duplicate
            # manifest/signature entries are malformed too and must fail closed.
            if (-not $seenNames.Add($entry.FullName)) {
                Stop-Verification "APK contains duplicate ZIP entry '$($entry.FullName)'."
            }
            if (Test-V1SignatureEntry $entry.FullName) { continue }
            $stream = $entry.Open()
            try {
                $sha = [Security.Cryptography.SHA256]::Create()
                try {
                    $digest = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
                } finally {
                    $sha.Dispose()
                }
            } finally {
                $stream.Dispose()
            }
            $payload.Add(
                $entry.FullName,
                [pscustomobject]@{ sha256 = $digest; length = [int64]$entry.Length }
            )
        }
        return $payload
    } finally {
        $archive.Dispose()
    }
}

function Assert-SamePayload([string] $UnsignedPath, [string] $SignedPath) {
    $unsigned = Get-ZipPayload $UnsignedPath
    $signed = Get-ZipPayload $SignedPath
    # SortedDictionary already exposes keys in ordinal order, avoiding the
    # culture- and case-insensitive ordering of Sort-Object.
    $unsignedNames = @($unsigned.Keys)
    $signedNames = @($signed.Keys)
    if (($unsignedNames -join "`n") -cne ($signedNames -join "`n")) {
        Stop-Verification 'test-signed APK changed the non-signature ZIP entry set.'
    }
    foreach ($name in $unsignedNames) {
        $left = $unsigned[$name]
        $right = $signed[$name]
        if ($left.sha256 -cne $right.sha256 -or $left.length -ne $right.length) {
            Stop-Verification "test-signed APK changed payload entry '$name'."
        }
    }
}

try {
    $paths = @($UnsignedReleaseApkPath, $TestSignedReleaseApkPath, $UnsignedInstrumentationApkPath, $TestSignedInstrumentationApkPath)
    foreach ($path in $paths) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Stop-Verification 'an APK input is missing.' }
    }

    $apksigner = Resolve-AndroidTool 'apksigner' 'build-tools\36.0.0\apksigner.bat'
    if ([string]::IsNullOrWhiteSpace($apksigner)) { Stop-Verification 'Android SDK apksigner was not found.' }

    # Verify the exact test-signed copies and that they use one certificate.
    & $apksigner verify --verbose $TestSignedReleaseApkPath 2>$null
    if ($LASTEXITCODE -ne 0) { Stop-Verification 'test-signed release APK failed apksigner verification.' }
    & $apksigner verify --verbose $TestSignedInstrumentationApkPath 2>$null
    if ($LASTEXITCODE -ne 0) { Stop-Verification 'test-signed instrumentation APK failed apksigner verification.' }
    $releaseCert = Get-CertificateDigest $apksigner $TestSignedReleaseApkPath
    $instrumentationCert = Get-CertificateDigest $apksigner $TestSignedInstrumentationApkPath
    $expectedCert = $ExpectedCertificateSha256.ToLowerInvariant()
    if ($releaseCert -ne $expectedCert -or $instrumentationCert -ne $expectedCert -or $releaseCert -ne $instrumentationCert) {
        Stop-Verification 'test-signed release and instrumentation certificates do not match the expected digest.'
    }

    # A signing-only derivative retains every payload entry except actual v1
    # signature artifacts; unrelated META-INF entries remain covered.
    Assert-SamePayload $UnsignedReleaseApkPath $TestSignedReleaseApkPath
    Assert-SamePayload $UnsignedInstrumentationApkPath $TestSignedInstrumentationApkPath

    $result = [ordered]@{
        status = 'pass'
        release_payload_derivation = 'apksigner-sign-only'
        unsigned_release_apk_sha256 = (Get-FileHash -LiteralPath $UnsignedReleaseApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        unsigned_instrumentation_apk_sha256 = (Get-FileHash -LiteralPath $UnsignedInstrumentationApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        tested_release_apk_sha256 = (Get-FileHash -LiteralPath $TestSignedReleaseApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        tested_instrumentation_apk_sha256 = (Get-FileHash -LiteralPath $TestSignedInstrumentationApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        test_signing_certificate_sha256 = $releaseCert
    }
    $json = $result | ConvertTo-Json -Depth 4
    if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
        $parent = Split-Path -Parent $OutputPath
        if (-not [string]::IsNullOrWhiteSpace($parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
        Set-Content -LiteralPath $OutputPath -Value $json -Encoding utf8
    }
    Write-Output $json
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
