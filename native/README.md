# Usage Ring native Codex runtime

This directory contains the fail-closed ARM64 Android JNI runtime. The JNI
boundary accepts only the six operations `start`, `beginDeviceLogin`,
`pollLogin`, `readRateLimits`, `logout`, and `shutdown`. `start` accepts only a
strict `filesDir`/`schemaVersion` DTO; the other five requests are JSON `null`
or `{}` only. Unknown fields and recursively nested credential/transport
escape-hatch fields are rejected, and responses contain only stable
status/error DTOs.

`start` additionally receives the application `Context` so
`rustls-platform-verifier` can initialize Android's system TrustManager. Its
JSON body contains only an absolute `filesDir` and `schemaVersion:1`. No raw
JSON-RPC method, token, shell command, tool, MCP server, or plugin can cross
the boundary.

## Upstream investigation

The public [`openai/codex` tag `rust-v0.148.0`](https://github.com/openai/codex/tree/rust-v0.148.0)
was resolved to commit
`3ba0f711642a888aec92a611a3f3b2211157ff89` (annotated tag object
`ab52d1794d47d47ecaeb0ec37fc00fa31593ecf3`). The app-server package is a
standalone Apache-2.0 adaptation under
`../third_party/openai-codex/patches/app-server`; provenance, licenses, and
modified-file hashes are recorded in
`../third_party/openai-codex/upstream.toml` and
`../third_party/openai-codex/PATCHES.md`.

The same provenance record covers two dependency-only security adaptations
without changing the Codex tag: a standalone `codex-git-utils` manifest selects
`gix` 0.83.0, and a patched `rama-dns` 0.3.0-alpha.4 selects Hickory 0.26.1.
Their source/API changes, license texts, and hashes are committed beside the
app-server patch. The native gate binds those hashes, including the
byte-identical `codex-git-utils/src` tree digest, into the exported runtime
marker, checks the exact locked versions, and rejects a stale `.so` containing
the former vulnerable version strings.

At that tag, [`codex-app-server`'s in-process runtime](https://github.com/openai/codex/blob/rust-v0.148.0/codex-rs/app-server/src/in_process.rs)
exposes `start(InProcessStartArgs)` and the
[`codex-app-server-client` facade](https://github.com/openai/codex/blob/rust-v0.148.0/codex-rs/app-server-client/src/lib.rs)
wraps it in `InProcessAppServerClient`.  The
[versioned account protocol](https://github.com/openai/codex/blob/rust-v0.148.0/codex-rs/app-server-protocol/src/protocol/v2/account.rs)
maps the requested controls as follows:

| JNI method | Public app-server operation | Boundary treatment |
| --- | --- | --- |
| `beginDeviceLogin` | `account/login/start` with `{type: chatgptDeviceCode}` | Return only verification URL/user code after a proven runtime integration |
| `pollLogin` | Drain `account/login/completed` notification | No polling endpoint is invented |
| `readRateLimits` | `account/rateLimits/read` | Keep only usage percentages/reset timestamps |
| `logout` | `account/logout` | Never accept credentials from the caller |

The runtime links the public in-process client and typed account protocol:
device-code login, event polling/cancel at 15 minutes, rate-limits read, and
logout. It forces the file auth store, `EnvironmentManager::without_environments`,
analytics/feedback/OTEL disabled, no state/log databases, and the patched
`PluginStartupTasks::Skip`. Rate limits are reduced to named five-hour and
seven-day fields (percent, minutes, reset epoch milliseconds); login exposes
only HTTPS verification URL and user code. Auth-refresh requests unsupported by
the pinned facade remain a sanitized runtime error. No authenticated probe was
run here.

## Reproducible gate

Run from the repository root with PowerShell:

```powershell
pwsh -File native/gate.ps1 -ReportPath native/gate-report.json
```

The command writes machine-readable JSON and exits `2` for `NO-GO`. It checks
Rust 1.95.0, the `aarch64-linux-android` target, NDK clang, Cargo unit tests,
the actual locked ARM64 release build, vendored app-server Skip/hash evidence,
reviewed gix/Hickory versions and patch hashes, linked JNI symbols/marker, and
absence of `openssl-sys` on the Android graph.
When these source and binary checks pass, the report may be static `GO`, but it
always keeps `release_ready=false`. A separate physical ARM64 evidence gate must
still prove verifier initialization, device login/poll/cancel, rate-limit
parsing, logout, and safe shutdown. No authenticated probe is run by this
script.

For an explicit source-compatibility attempt (large, unauthenticated download)
add `-ProbeUpstream`. The probe is advisory source-compatibility evidence only;
it is kept separate from the reviewed vendored runtime and cannot invalidate a
successful local build merely because the unpatched upstream graph still
selects OpenSSL. It does not read credentials or contact an authenticated
endpoint.
