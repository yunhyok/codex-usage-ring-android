# Usage Ring native feasibility spike

This directory is a fail-closed ARM64 Android JNI spike.  It is not an APK
release implementation and it does not contain a Codex binary or an HTTP
client.  The JNI boundary accepts only the six operations `start`,
`beginDeviceLogin`, `pollLogin`, `readRateLimits`, `logout`, and `shutdown`.
Requests are JSON `null` or `{}` only.  Unknown fields and recursively nested
credential/transport escape-hatch fields are rejected, and responses contain
only stable status/error DTOs.  No raw JSON-RPC method, token, shell command,
tool, MCP server, or plugin can cross the boundary.

## Upstream investigation

The public [`openai/codex` tag `rust-v0.148.0`](https://github.com/openai/codex/tree/rust-v0.148.0)
was resolved to commit
`3ba0f711642a888aec92a611a3f3b2211157ff89` (annotated tag object
`ab52d1794d47d47ecaeb0ec37fc00fa31593ecf3`).  The source is not vendored;
the pin and API paths are recorded in [upstream.toml](upstream.toml).

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

The in-process client startup requires a large runtime/config graph (core,
exec environment, state/log databases, auth loading, and feedback).  Android
TLS trust, keyring/auth-store behavior, SQLite/runtime assumptions, and plugin
startup were not proven by this scaffold.  An explicit cross-check of the
versioned `codex-app-server-client` workspace reached its first concrete
Android blocker at `openssl-sys v0.9.111`: it could not locate an Android
OpenSSL/sysroot/pkg-config configuration (`OPENSSL_SYS_ANDROID_MISSING`).
Therefore the runtime is *not* linked and the gate remains `NO-GO`; the methods
that could imply authentication return a sanitized `NOT_READY`/`unavailable`
status.  No login or authenticated probe was run.

## Reproducible gate

Run from the repository root with PowerShell:

```powershell
pwsh -File native/gate.ps1 -ReportPath native/gate-report.json
```

The command writes machine-readable JSON and exits `2` for `NO-GO`.  It checks
the Rust 1.95.0 pin, the `aarch64-linux-android` target, the NDK clang linker,
Cargo unit tests, the real ARM64 cross-build, and the public upstream tag
object.  Any missing prerequisite or failed command is a precise blocker; no
successful login is inferred from a scaffold build.  `native/gate-report.json`
is generated evidence and should not be committed unless a release record
explicitly calls for it.

For an explicit source-compatibility attempt (large, unauthenticated download)
add `-ProbeUpstream`.  The gate then shallow-checks out the pinned tag into a
temporary directory and runs `cargo check -p codex-app-server-client` for
`aarch64-linux-android`; the command and tail of its output are included in the
JSON report.  The probe does not read credentials or contact an authenticated
OpenAI endpoint.
