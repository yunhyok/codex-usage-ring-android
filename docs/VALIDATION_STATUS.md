# Validation status

This file is a release record, not a blanket assurance. An item is **pass**
only when its command output and, where applicable, its evidence file are
attached to the release review. Until then it remains **not run** or **blocked**.

| Check | Required evidence | Status |
| --- | --- | --- |
| Android Studio Quail 3 toolchain | AI-261 install, JDK 17, SDK 36, NDK 28.2, AVD inventory | **local setup complete; API 29/31/33/34/35/36 AVD install and launch recorded, IDE interactive sync not recorded** |
| `testMockDebugUnitTest`, `lintMockDebug`, `assembleMockDebug` | Local/CI log and mock APK artifact | **local pass (2026-08-21); public main CI pass** |
| `assembleNativeDebug` JNI packaging | APK contains `lib/arm64-v8a/libusage_ring_codex.so` | **local scaffold pass; not runtime readiness** |
| Responsive widget and quiet notification | API 36 pin/resize, permission, notification panel and manager state | **local mock pass; API 31/33/34/35 launch regression pass; physical device pending** |
| Rust `fmt`, `clippy`, tests | Local/CI log | **local and public main CI pass: 5/5 tests** |
| Native Android gate | Sanitized local report; release requires reviewed `docs/evidence/v0.1.0/native-gate.json` | **NO-GO: upstream OpenSSL portability failure and runtime not linked/proven** |
| Physical-device install/launch/widget smoke | `docs/evidence/v0.1.0/physical-device.json` plus device metadata | **missing** |
| CycloneDX dependency SBOM and license policy | Gradle `cyclonedxBom` plus `scripts/verify-sbom.ps1` | **local and public main CI pass: 321 components, 3 exact metadata exceptions; release artifact pending** |
| Cargo dependency license policy | locked `cargo metadata` plus `scripts/verify-cargo-licenses.ps1` | **local and public main CI pass: 41 locked packages** |
| Signed APK verification and SHA-256 | Release workflow artifact | **not run** |
| SBOM and build attestation | Release workflow artifact | **not run** |
| Fresh install / upgrade / uninstall | Disposable-device checklist | **not run** |

Do not change a status to pass by editing this table alone. Add reproducible
evidence, reviewer and date in the release PR. Missing evidence deliberately
causes the release workflow to stop.

The ordinary CI workflow may upload a `NO-GO` feasibility report while still
passing its mock/scaffold checks. Only the tag-triggered release workflow runs
the hard `nativeGate` task, and that task exits non-zero until the Android
Codex runtime, real login, and physical-device requirements are proven.
