# Historical native gate result: NO-GO

> This is the archived portability result from the initial scaffold attempt.
> It is retained for auditability and is not a current release verdict; the
> current native integration must produce a fresh reviewed gate record.

Date: 2026-08-21 (Asia/Seoul)

Usage Ring does **not** currently ship an APK. The fail-closed JNI boundary is
buildable for Android ARM64, but the pinned public Codex runtime is not yet an
Android-ready dependency. This is the stop condition defined by the project
plan, not a mock/UI failure.

## Proven locally

- Rust `1.95.0`, target `aarch64-linux-android`, NDK `28.2.13676358`, API 29
  linker.
- `cargo fmt --check`, `cargo clippy -- -D warnings`, and five native boundary
  tests pass.
- The boundary cross-build produces `libusage_ring_codex.so` (ARM64). The
  final local rerun with release line-table debug information produced
  5,354,576 bytes, SHA-256
  `d1492fa19822aa82b0010113bf8c400c4319ee136f78c08c88f53b8b13392426`.
  This is a local build observation, not a reproducible-release claim or a
  functioning Codex runtime.
- The JNI boundary exposes only `start`, `beginDeviceLogin`, `pollLogin`,
  `readRateLimits`, `logout`, and `shutdown`; credential-shaped and arbitrary
  RPC/tool/MCP/plugin fields are rejected.
- The public source pin resolves `rust-v0.148.0` to annotated tag object
  `ab52d1794d47d47ecaeb0ec37fc00fa31593ecf3` and commit
  `3ba0f711642a888aec92a611a3f3b2211157ff89`.

## First upstream Android blocker

An unauthenticated source probe ran:

```powershell
pwsh -File native/gate.ps1 -ProbeUpstream
```

The probe reaches `openssl-sys 0.9.111` while checking the pinned
`codex-app-server-client` for `aarch64-linux-android`, then stops because it
cannot locate an Android OpenSSL installation/sysroot/pkg-config configuration
(`OPENSSL_SYS_ANDROID_MISSING`). The project intentionally does not inject an
unreviewed OpenSSL build, remove TLS validation, or switch to an undocumented
usage endpoint to bypass this failure.

The scaffold therefore also retains the independent hard blocker
`CODEX_RUNTIME_NOT_LINKED`. Android TLS trust, device-code login, token refresh,
plugin/MCP suppression, process recovery, and real `account/rateLimits/read`
remain unproven. No credentials were read and no authenticated request was
made during this gate.

## Reproduction and release effect

See [`native/README.md`](../native/README.md) and the machine-readable
[`native-gate-no-go.json`](evidence/v0.1.0/native-gate-no-go.json). A local
report can be regenerated with `native/gate.ps1`; generated reports and native
build products are ignored by Git.

The `v0.1.0` release workflow requires a separately reviewed
`native-gate.json` with `status: pass` plus physical-device evidence. Neither
exists. Consequently no tag, signed APK, release keystore, or GitHub
pre-release is created from this result.
