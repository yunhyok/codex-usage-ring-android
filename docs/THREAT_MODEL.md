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

The source manifest directly requests the following permissions:

- `POST_NOTIFICATIONS`: requested only after the user enables the quiet status
  notification on Android 13 or newer.
- `INTERNET`: required by the in-process Codex client for system-verified TLS.
- `RECEIVE_BOOT_COMPLETED`: used by the app's non-exported boot receiver to
  restore persisted periodic WorkManager scheduling.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`: used only by the
  non-exported, user-started device-login polling service. That service stops
  on success, cancellation, failure, or the 15-minute limit. The ordinary
  usage-status notification is not a foreground-service notification.

The final merged APK also contains AndroidX WorkManager support permissions,
verified with `aapt dump badging`:

- `ACCESS_NETWORK_STATE`: enforces the connected-network refresh constraint.
- `WAKE_LOCK`: lets WorkManager finish a scheduled refresh safely.
- the package-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`: AndroidX's
  signature-level guard for dynamically registered non-exported receivers.

The release reviewer must compare the merged manifest and `apkanalyzer`
permission output with this document. Any unexplained permission is a release
blocker.

## TLS revocation-fetch exception

The application keeps `android:usesCleartextTraffic="false"`, trusts only the
Android system certificate store, and does not disable certificate or hostname
verification. Its [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
contains one exact-domain exception: cleartext is permitted for
`c.pki.goog` with `includeSubdomains="false"`. This is limited to the public
CA certificate-revocation-list distribution request made by Android's platform
verifier; the Codex authentication and API connections remain HTTPS.

This narrow exception addresses the Android CRL-fetch behavior documented by
the platform-verifier maintainers in
[rustls-platform-verifier PR #179](https://github.com/rustls/rustls-platform-verifier/pull/179).
There is no wildcard, user/raw trust anchor, debug override, private CA, or
webpki fallback. If the live certificate's CRL distribution host changes,
login must fail closed until the new chain and exact host are reviewed and the
physical TLS/login gate is repeated.

The verifier implementation is vendored and source-visible under
`../third_party/rustls-platform-verifier-android/` from rustls-platform-verifier
v0.7.0 commit `996b1c903491641b17b3c9afb65d1352f6fc6b76`. Its PKIX checker
retains `SOFT_FAIL` and `ONLY_END_ENTITY`; `PREFER_CRLS` is added only when no
stapled OCSP response exists. The OCSP/CRL fallback path remains enabled and
`NO_FALLBACK` is omitted. Its system trust-anchor collision scan advances for
deleted or malformed anchors, avoiding an unbounded verification loop. CI
checks the exact JNI package/signature, `BuildConfig.TEST = false`, vendored
source hashes, collision-scan invariant, and these option invariants before
every native compile/package path. The mock flavor does not compile the
verifier source.

## Out of scope

This model does not claim protection against a rooted device, a malicious OS,
compromised Android Studio/SDK binaries, or a developer machine that already
leaks secrets. Report suspected vulnerabilities privately according to
[`SECURITY.md`](../SECURITY.md).
