# Privacy and threat model

## Scope

The widget displays usage information supplied by the app and its native
module. The expected trust boundary is the Android application sandbox plus
the local device storage used by the app. The project does not include a
backend, telemetry service, or account system.

## Assets and threats

| Asset | Threat | Mitigation / verification |
| --- | --- | --- |
| Usage values and timestamps | Disclosure through logs, backups, or a debug build | Keep values on-device; avoid verbose logs; inspect release manifest and APK; do not distribute `debug` APKs |
| Android widget state | Malicious or stale exported component | Keep components non-exported unless required; use explicit intents and validate inputs |
| Native Rust boundary | Memory-safety or malformed-input crash | Rust safe defaults; `fmt`, `clippy`, tests, and the native gate in CI; fuzzing is future work |
| Build/signing credentials | Credential theft or supply-chain compromise | GitHub environment secrets only; no key generation or secret files in the repo; least-privilege workflow permissions |
| Dependency graph | Vulnerable or compromised dependency | Dependency review and lockfiles; review updates before merge; SBOM on release |
| User privacy | Accidental network/identifier/permission collection | Review merged manifest and APK; expected permissions are documented; any expansion needs a threat-model update |

## Expected permissions

The source manifest directly requests `POST_NOTIFICATIONS`, which is presented
only after the user enables the quiet status notification on Android 13 or
newer. The final merged APK also contains the following AndroidX WorkManager
support permissions, verified with `aapt dump badging`:

- `ACCESS_NETWORK_STATE`: enforces the connected-network refresh constraint.
- `WAKE_LOCK`: lets WorkManager finish a scheduled refresh safely.
- `RECEIVE_BOOT_COMPLETED`: lets WorkManager restore persisted periodic work
  after reboot; Usage Ring does not export its own boot receiver.
- the package-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`: AndroidX's
  signature-level guard for dynamically registered non-exported receivers.

WorkManager's optional generic `FOREGROUND_SERVICE` permission is explicitly
removed from the merged manifest because the current refresh worker never uses
the foreground path.

The native login implementation will additionally need `INTERNET`,
`FOREGROUND_SERVICE`, and a user-started `dataSync` foreground-service
declaration, but those permissions
must not be added to a distributable release until the native gate passes and
the service exists. The release reviewer must compare the merged manifest and
`apkanalyzer` permission output with this document. Any unexplained permission
is a release blocker.

## Out of scope

This model does not claim protection against a rooted device, a malicious OS,
compromised Android Studio/SDK binaries, or a developer machine that already
leaks secrets. Report suspected vulnerabilities privately according to
[`SECURITY.md`](../SECURITY.md).
