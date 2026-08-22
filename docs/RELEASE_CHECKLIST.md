# Usage Ring `v0.1.0` release checklist

The release is a GitHub **pre-release** only. Never publish a release from a
working tree that has not passed the hard native gate.

## Before tagging

- [ ] Review the diff, dependency changes, merged manifest, and
      [`docs/THREAT_MODEL.md`](THREAT_MODEL.md).
- [ ] Validate with Android Studio Quail 3 and JDK 17.
- [ ] Run `testMockDebugUnitTest`, `lintMockDebug`, and
      `assembleMockDebug`; keep the CI run URL and artifact.
- [ ] Run Rust `fmt --check`, `clippy -- -D warnings`, and native tests.
- [ ] Run the native gate on the release/native path. A mock build is not a
      substitute.
- [ ] Record the complete physical-device matrix in
      `docs/evidence/v0.1.0/physical-device.json`: native load, Android system
      TLS, device-code login, restart/token refresh, process recovery, real
      rate-limit read, 25 refreshes, widget resize, notification dismissal and
      restoration, offline recovery, logout/re-login, reboot recovery,
      secret-log scan, plugin/MCP suppression, and uninstall.
      Include the candidate source commit, exact repository Actions run URL for
      a successful CI run at that commit, and SHA-256 values for the debug APK,
      unsigned native release APK, instrumentation APK, and packaged native
      library. Record separate `raw_native_library_sha256` (the native-gate
      output) and `packaged_native_library_sha256` (the exact pinned NDK
      `llvm-strip --strip-unneeded` derivation embedded in both APKs); these
      hashes must not be conflated. The release workflow verifies candidate ancestry and permits only
      the two evidence JSON files in the candidate-to-tag diff. It downloads
      the exact candidate payload and signs it without rebuilding app/native
      code; it reports the final production-signed APK hash separately. The
      debug APK test is development-only. Before marking the record complete,
      download the exact unsigned `nativeRelease` and instrumentation artifacts
      from that CI run, create disposable test-signed copies with `apksigner
      sign` only (no Gradle, rebuild, zipalign, or repack), and run both on the
      physical ARM64 device through Android Studio. Use the same local test
      certificate for both copies, record the two resulting SHA-256 values and
      the certificate SHA-256, set `release_payload_derivation:
      apksigner-sign-only`, and mark both `native_release_install` and
      `native_release_instrumentation` as `pass`. Do not create or use the
      production release keystore locally. A disposable test keystore may exist
      outside the repository only for this review; never derive it from, treat
      it as, commit, upload, or retain it as production signing material. Delete
      it and the signed test copies after review.
      `restart_token_refresh` must not be marked pass from an arbitrary elapsed
      time or a successful rate-limit read alone: the pinned auth manager may
      retain old auth after a failed refresh and the rate-limit response has no
      refresh marker. Use only a reviewed, source-backed natural-expiry proof or
      deterministic non-secret test signal; never inspect or modify token data.
- [ ] Review permissions and APK contents; verify that no debug certificate,
      unexpected network permission, or secret is present.

## Release workflow gates

`.github/workflows/release.yml` fails closed unless all of the following are
true:

1. The tag is exactly `v0.1.0` and the workflow is running from the expected
   repository.
2. The native gate job passed and
   `docs/evidence/v0.1.0/native-gate.json` records `status: pass`.
3. Physical-device evidence exists, records `status: pass`, includes a device
   model, Android API level, test date, and reviewer, and records `pass` for
   every physical-device check listed above. It also records the unsigned
   candidate hashes, the two test-signed APK hashes, the test certificate hash,
   and `pass` for both `native_release_install` and
   `native_release_instrumentation`. A debug APK smoke run alone is not release
   evidence.
4. External signing secrets are present only in the signing step; the workflow
   never creates a key and deletes the materialized keystore immediately after
   signing.
5. The APK is signed, verifiable, and its `apksigner --print-certs` SHA-256
   matches the independently reviewed pinned signing policy.
6. The exact candidate SBOM contains an explicit rustls platform-verifier
   component. Both the signed-APK SPDX SBOM and the source-tree/lockfile SPDX
   SBOM (covering `native/Cargo.lock`) are uploaded, along with the candidate
   Gradle CycloneDX SBOM and GitHub build attestation.

## Rollback / stop conditions

Stop the release if any gate is missing, if any candidate or test-signed APK
hash changes after review, if the test-signed nativeRelease payload was rebuilt
or repackaged,
if a permission is unexplained, if native and mock behavior diverge, or if a
device smoke test fails. Delete the GitHub pre-release and revoke/rotate
credentials if signing material is exposed. Do not silently replace evidence;
add a corrected evidence record and reviewer note.
