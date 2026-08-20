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
- [ ] Record a physical-device install, launch, widget placement/refresh, and
      uninstall smoke test in `docs/evidence/v0.1.0/physical-device.json`.
- [ ] Review permissions and APK contents; verify that no debug certificate,
      unexpected network permission, or secret is present.

## Release workflow gates

`.github/workflows/release.yml` fails closed unless all of the following are
true:

1. The tag is exactly `v0.1.0` and the workflow is running from the expected
   repository.
2. The native gate job passed and
   `docs/evidence/v0.1.0/native-gate.json` records `status: pass`.
3. Physical-device evidence exists, records `status: pass`, and includes a
   device model, Android API level, test date, and reviewer.
4. External signing secrets are present. The workflow never creates a key.
5. The APK is signed, verifiable, and hashed with SHA-256.
6. An SBOM and GitHub build attestation are generated and uploaded.

## Rollback / stop conditions

Stop the release if any gate is missing, if the APK hash changes after review,
if a permission is unexplained, if native and mock behavior diverge, or if a
device smoke test fails. Delete the GitHub pre-release and revoke/rotate
credentials if signing material is exposed. Do not silently replace evidence;
add a corrected evidence record and reviewer note.
