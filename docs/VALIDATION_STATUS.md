# Validation status

This file is a release record, not a blanket assurance. An item is **pass**
only when its command output and, where applicable, its evidence file are
attached to the release review. Until then it remains **not run** or **blocked**.

| Check | Required evidence | Status |
| --- | --- | --- |
| Android Studio Quail 3 toolchain | AI-261 install, JDK 17, SDK 36, NDK 28.2, AVD inventory | **local setup complete; Temurin 17 interactive IDE sync pass; API 29/31/33/34/35/36 AVD install and launch recorded** |
| `testMockDebugUnitTest`, `lintMockDebug`, `assembleMockDebug` | Local/CI log and mock APK artifact | **local mock/native unit tests and lint pass (2026-08-22, lint errors 0); public main CI pass applies to the prior pushed commit** |
| `assembleNativeDebug` JNI packaging | APK contains `lib/arm64-v8a/libusage_ring_codex.so` | **local ARM64 runtime package/static pass; API 36 physical native load, authenticated rate-limit read, 25-refresh loop, exact six-method JNI surface, and disabled plugin/MCP/shell/telemetry policy pass; remaining physical gate pending** |
| Responsive widget and quiet notification | API 36 pin/resize, permission, notification panel and manager state | **local mock pass; API 31/33/34/35 launch regression pass; API 36 physical notification cancel/repost and widget binding/resize-options pass** |
| Rust `fmt`, `clippy`, tests | Local/CI log | **local fmt and ARM64 check/clippy/build pass; Windows host test is advisory-blocked by pinned upstream PTY ABI types; branch Linux CI pending** |
| Native Android gate | Sanitized local report; release requires reviewed `docs/evidence/v0.1.0/native-gate.json` | **local static GO and partial physical pass, `release_ready=false`; actual token-expiry refresh, final uninstall, and reviewed release evidence remain pending** |
| Physical-device install/launch/widget smoke | `docs/evidence/v0.1.0/physical-device.json` plus device metadata | **local development-only partial pass (2026-08-22): install, launch, native load, system TLS/device-code login, logout/re-login, process and reboot recovery, rate-limit read, 25 refreshes, widget binding/resize options, notification cancel/repost, offline recovery, and plugin/MCP policy; actual token-expiry refresh, uninstall, and reviewed CI-bound evidence pending. The primary run used nativeDebug; a separate non-CI nativeRelease smoke also passed but cannot satisfy the release proof.** |
| Exact unsigned nativeRelease physical proof | Test-signed derivative hashes, same test certificate, Android Studio install/runtime and instrumentation record | **blocked for release evidence: the exact CI unsigned nativeRelease and instrumentation artifacts have not been test-signed with a disposable local certificate and installed/tested, so `native_release_install` and `native_release_instrumentation` remain pending. Separately, a local non-CI nativeRelease copy was signing-only derived with the existing debug certificate; payload comparison, install/launch, policy, 25 authenticated reads, WorkManager, widget, and debug-candidate restoration passed. This is development evidence only.** |
| CycloneDX dependency SBOM and license policy | Gradle `cyclonedxBom` plus `scripts/verify-sbom.ps1` | **local pass: 321 components, 3 exact metadata exceptions; branch CI and release artifact pending** |
| Cargo dependency license policy | locked `cargo metadata` plus `scripts/verify-cargo-licenses.ps1` | **local pass: 1,134 locked packages; branch CI pending** |
| Signed APK verification and SHA-256 | Release workflow artifact | **not run** |
| SBOM and build attestation | Release workflow artifact | **not run** |
| Fresh install / upgrade / uninstall | Disposable-device checklist | **local install and data-preserving upgrade pass; final uninstall pending** |

Do not change a status to pass by editing this table alone. Add reproducible
evidence, reviewer and date in the release PR. Missing evidence deliberately
causes the release workflow to stop.

The 2026-08-22 physical results are recorded only as sanitized local files
under `app/build/reports/local-native-login-nsc-v5-device-api36/` and
`app/build/reports/local-native-relogin-patch-v5-device-api36/`, which are
gitignored. They contain no serial number, account identity, pairing code,
verification URL, token, actual usage value, or raw device output. They do not
replace the reviewed, CI-commit-bound `physical-device.json` required for a
release.

The ordinary CI workflow may pass mock and static ARM64 checks while still
lacking physical-device evidence. The CI candidate artifact now contains the
exact unsigned native release APK, debug APK, instrumentation APK, static
reports/SBOM, and native library. The tag-triggered release workflow validates
candidate ancestry and Actions run metadata, signs that exact unsigned payload
without rebuilding app/native code, and stops until the Android Codex runtime,
real login, and physical-device requirements are proven. In particular, a
nativeDebug device run is retained only as development evidence; release
evidence must include the separately test-signed derivative hashes and the two
nativeRelease proof statuses described in
[`docs/evidence/v0.1.0/README.md`](evidence/v0.1.0/README.md).

Native-library evidence uses two distinct digests: `raw_native_library_sha256`
is the unstripped native-gate output (`release_so_sha256`), while
`packaged_native_library_sha256` is independently derived by the pinned NDK
28.2 `llvm-strip --strip-unneeded` operation and must match the embedded
`lib/arm64-v8a/libusage_ring_codex.so` in both candidate APKs and the final
signed APK. A raw-versus-packaged hash difference is expected and is not a
release inconsistency when the derivation check passes.
