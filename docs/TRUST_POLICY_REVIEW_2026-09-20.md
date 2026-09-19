# Android trust-policy review — 2026-09-20

The release preparation review found that the vendored verifier initialized
its production `TrustManagerFactory` with a non-null `AndroidCAStore`. That
store includes user-added CAs. AOSP's factory uses a keystore-specific
configuration for a non-null argument; only a null argument selects the
application's Network Security Configuration. Consequently, the previous
verifier could accept a user-added CA despite this app declaring system-only
anchors. The disposable-emulator regression reproduced that behavior.

References: [AOSP RootTrustManagerFactorySpi](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/security/net/config/RootTrustManagerFactorySpi.java#55),
[Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config#CustomTrust).

## Correction

The production manager and root-enumeration helper now initialize the factory
with a null keystore. The app's existing system-only anchor policy applies.
The separately loaded `AndroidCAStore` remains available for the secondary
PKIX revocation check. A chain rejected by the configured primary manager
returns immediately, so the later check cannot broaden trust. Rust hostname
verification, CRL/OCSP options, error mapping and JNI methods are unchanged.

The mock instrumentation APK compiles the same verifier source and the
release-only `BuildConfig.TEST = false` shim. The mock application itself does
not package the verifier. This enables the opt-in regression on a disposable
x86 emulator without loading the ARM64 native runtime.

## Independent review and provenance

The read-only `native_review` reviewer identified the original trust-policy
defect, then reviewed the correction and approved the corrected source and
provenance scope. The coordinating agent independently checked the AOSP
behavior, local call flow, pinned source hashes and emulator results.

- Upstream commit: `996b1c903491641b17b3c9afb65d1352f6fc6b76`.
- Upstream Kotlin SHA-256: `3d6f8593cdb87af14265ebd28e20c471adbd3d7c8766b3ee543b85e92e928561`.
- Corrected Kotlin SHA-256: `0a345c5237f7d6c9d11f2709c520a4969b4753f22e3f3e134f8248ab80224920`.
- Both retained license files match the pinned upstream bytes.
- Local adaptations are the provenance header, application trust-policy
  selection/store loading, conditional CRL preference and collision-scan fix.

`PROVENANCE.json` approval applies only to those reviewed bytes. It does not
approve a production signing certificate, physical release matrix or APK
publication.

## Reproducible regression

`NativeTrustPolicyDeviceTest` contains one offline test. The test requires
`usageRingTrustPolicyEvidence=true`; with that argument, a missing fixture
fails rather than being counted as a successful skip. It confirms that the
legacy store accepts the installed fixture chain, requires the actual
vendored production manager to reject it, and checks acceptance of a current
system root. Only public test certificates are committed; no private key is
retained. The fixture is valid through 2045 and identifies `trust-policy.test`.

On a **disposable, rooted emulator only**, install
`app/src/androidTest/assets/trust_policy_user_ca.cer` as
`/data/misc/user/0/cacerts-added/fec2085f.0`, owned by `system:system`, mode
`0644`, with its Android SELinux context restored. Never install the fixture
on a personal device. Build/install `mockDebug` and `mockDebugAndroidTest`,
then run the single class with the opt-in argument:

```text
adb -s <disposable-emulator> shell am instrument -w -e class io.github.yunhyok.usagering.NativeTrustPolicyDeviceTest -e usageRingTrustPolicyEvidence true io.github.yunhyok.usagering.test/androidx.test.runner.AndroidJUnitRunner
```

Coordinator-controlled API 36 results with exact verifier source and the
production Network Security Configuration in an isolated minimal harness:

| Source | Result |
| --- | --- |
| Previous verifier `ff38c728…` | 1 test, 1 expected failure: user CA accepted |
| Corrected verifier `0a345c52…` | 1 test passed: user CA rejected, system root accepted |
| Actual `mockDebug` app and mock instrumentation | The same opt-in test passed |

The isolated short-path project build also passed 41 JVM tests, mock lint
(zero errors), `assembleNativeRelease`, and both mock/native instrumentation
APK builds. The Windows OneDrive checkout encountered an incremental-build
file lock, so these completed builds used the matching short-path source copy.

Sanitized local records are in
`app/build/reports/release-prep-20260920/trust-policy-before.json` and
`trust-policy-after.json`. These checks do not claim a public-endpoint TLS
handshake, real account authentication, or final ARM64 release validation.
