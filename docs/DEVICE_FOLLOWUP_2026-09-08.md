# Physical-device follow-up — 2026-09-08

The existing API 36 ARM64 installation was upgraded in place with the exact
unsigned nativeRelease payload from successful [main CI run 34171317572](https://github.com/yunhyok/codex-usage-ring-android/actions/runs/34171317572),
commit `4ab0b691e46975aa0251a93dfec84473fe29f550`.
The installed certificate matched the existing local development certificate;
only `apksigner sign` was applied to the CI application and instrumentation
APKs. The payload verifier confirmed unchanged ZIP entries and matching signers.
`adb install -r` preserved app data, login state, and the existing widget.

| Check | Result |
| --- | --- |
| Exact CI payload, ARM64 library strip derivation, signing certificate | Pass |
| Data-preserving nativeRelease upgrade and launch | Pass |
| Native policy, existing widget options, active periodic work | 5 tests passed |
| Samsung launcher resize of the existing widget | User confirmed; system row/column span both changed from 2/3 to 1/1 |
| Final widget dimensions reported by the launcher | 70 × 100 dp; dark mode enabled |
| Authenticated ordinary usage refresh | Failed: repository returned an error snapshot |
| 25-read repetition | Not run after the first failed read |
| Natural token refresh | Pending; no baseline/expiry proof was claimed |

The login assertion checks persisted application state, not the native auth
manager's current validity. A validated network also does not prove that the
account endpoint, TLS, or credentials are usable. The original binary maps
all typed rate-limit request failures to one `RATE_LIMITS_UNAVAILABLE` code.

Sanitized local records are under
`app/build/reports/physical-device-20260908/`: `static-apk.json`,
`signed-payload.json`, `structural-tests.json`, `online-refresh.json`, and
`launcher-resize.json`. They contain no device serial, account identifier,
credentials, actual usage values, or raw device output. Launcher screenshots
were not collected; resize was checked using user confirmation and system
widget options.

This is partial device evidence. `release_ready` remains false. Fresh login,
logout/relogin, network toggling, reboot, target uninstall, and natural-refresh
expiry checks were not performed in this data-preserving follow-up.

## Narrow failure diagnostic

The follow-up change preserves typed failures as fixed `RATE_LIMITS_*` codes
instead of collapsing all failures. It changes neither auth acquisition nor
request/retry behavior. The first local diagnostic reported `RATE_LIMITS_BACKEND`.
A narrower classification recognizes only the pinned error prefix/header;
response bodies are ignored and no raw error, URL, token, or account identifier is emitted.

Run the opt-in check only on the native candidate with its matching signed
instrumentation APK. It calls native `start()` and one ordinary
`readRateLimits()`; failure output contains only a closed category allowlist.
It does not establish natural-refresh evidence or replace the normal
repository, 25-read, and release acceptance tests.

```text
adb shell am instrument -w -e usageRingNativeDiagnostic true -e class io.github.yunhyok.usagering.NativeConnectivityDeviceTest#ordinaryNativeReadReportsSanitizedFailure io.github.yunhyok.usagering.test/androidx.test.runner.AndroidJUnitRunner
```