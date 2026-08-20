# Usage Ring

Usage Ring is an unofficial, independent Android widget for showing a compact
Codex usage indicator. It is a Codex-only project: the source in this
repository is the source of truth, and no OpenAI logo or undocumented/private
API is used. A source build does not need an account; live usage requires the
official Codex App Server device-code flow once the Android native gate passes.

> **Unofficial / no endorsement:** Usage Ring is not affiliated with,
> sponsored by, or endorsed by OpenAI. “Codex” identifies the compatible usage
> source; it does not imply partnership or approval. Do not add OpenAI marks or
> logos when redistributing the app.

The layout and interaction ideas were informed by the public **Codelight**
project as a design reference. Codelight is credited here for inspiration only;
its code, assets, and licenses are not copied into this repository. See
[`NOTICE`](NOTICE) for the attribution boundary.

## Status

This repository is pre-release. The target milestone is `v0.1.0` (GitHub
pre-release). A release is blocked until the native Rust gate and physical
Android-device evidence are present and independently reviewed. The current
verification record is intentionally explicit in
[`docs/VALIDATION_STATUS.md`](docs/VALIDATION_STATUS.md); placeholders are not
claims that a check passed.

The current native decision is **NO-GO** because the pinned Codex runtime stops
at an Android OpenSSL portability dependency before it can be linked. The JNI
boundary and mock app are testable, but no APK release is authorized. See the
reproducible [`native gate report`](docs/NATIVE_GATE_NO_GO.md).

## Quick start

Prerequisites:

- Windows 11, macOS, or Linux with Android Studio **Quail 3** (or the exact
  patch selected by the release notes).
- Android SDK Platform/Build Tools versions declared by the project.
- JDK 17 (Android Studio's bundled JetBrains Runtime is acceptable).
- Rust stable with `rustfmt` and `clippy` components for the native module.

From a clone:

```text
git clone https://github.com/yunhyok/codex-usage-ring-android.git
cd codex-usage-ring-android
```

Open the directory in Android Studio, let Gradle sync, and select the
`mockDebug` variant when working without native device integration. Build and
install the mock variant from a connected emulator/device:

```powershell
.\gradlew.bat --no-daemon testMockDebugUnitTest lintMockDebug assembleMockDebug
.\gradlew.bat --no-daemon installMockDebug
```

The mock variant is useful for UI development and deterministic tests. It is
not evidence that the Rust/native path works. Native validation must run the
native gate described in [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md)
and must include physical-device evidence.

The Android Studio AVD and physical-device matrix is documented in
[`docs/ANDROID_STUDIO_VALIDATION.md`](docs/ANDROID_STUDIO_VALIDATION.md).

## Permissions and privacy

Usage Ring should request only the permissions listed in the app manifest. A
fresh install must be reviewed against the expected list in
[`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md). The app is designed to keep
usage data on-device and to avoid analytics, advertising identifiers, contacts,
location, microphone, camera, and network access unless a future change is
documented, reviewed, and justified. Do not treat this statement as a promise
for unreviewed builds: verify the merged manifest and APK before distribution.

## Build variants and release gate

- `mockDebug`: JVM/UI development path with native calls stubbed or mocked.
- Native/release path: must pass Rust `fmt`, `clippy`, and tests, the Android
  native gate, APK verification, and physical-device smoke evidence.

The local fail-closed preflight is:

```powershell
.\scripts\release\preflight.ps1 -Tag v0.1.0 `
  -EvidenceDir docs/evidence/v0.1.0 `
  -ApkPath path\to\signed-release.apk `
  -RequireSigning
```

It never creates a keystore or accepts secrets from files committed to the
repository. Supply signing credentials through the CI secret store or a secure
local environment only. The release workflow will publish a GitHub pre-release
only after every gate passes; see [`.github/workflows/release.yml`](.github/workflows/release.yml).
The same workflow emits both an SPDX source/APK SBOM and a CycloneDX Gradle
dependency SBOM; `native/Cargo.lock` pins the Rust dependency graph.
`scripts/verify-sbom.ps1` fails CI when a component has neither an approved
license nor an exact reviewed metadata exception;
`scripts/verify-cargo-licenses.ps1` independently checks every locked Rust
package's SPDX expression.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md), [`SECURITY.md`](SECURITY.md), and
the [Code of Conduct](CODE_OF_CONDUCT.md) before opening an issue or pull
request. Keep machine-local endpoint/model settings and signing material out of
Git. All changes need tests or a documented reason why a test is not practical.

## License

Source code is available under the Apache License 2.0. See [`LICENSE`](LICENSE)
and [`NOTICE`](NOTICE). Third-party dependencies retain their own licenses.
