# Compact widget and refresh-path review — 2026-09-08

## Scope and findings

Reviewed the original Codex-only compact usage indicator described in [README](../README.md), the widget/notification display, manual and scheduled refresh callers, repository persistence, and the Kotlin/JNI/App Server usage-read path. This review extends existing PR #2; it does not certify an APK release.

| Finding | Corrected behavior | Regression evidence |
| --- | --- | --- |
| Widget provider required 180×100dp and offered only two large layouts | Default 1×1 cells; 40dp minimum and resize minimum for older launchers; exact-size centered ring with percentage inside | `WidgetMetadataTest`; `WidgetRenderingDeviceTest` |
| Purple ring and app icon were difficult to see against a dark background | Shared `usage_accent` resource `#80F0A0` for live ring, app icon and dashboard indicator; stale/unknown/error retain distinct neutral presentation | Pixel checks and rendered screenshots |
| Manual refresh reached synchronous JNI on the Compose main thread | `NativeUsageSource.fetch` performs native calls on `Dispatchers.IO` | `NativeUsageSourceTest.blockingNativeCallsLeaveTheCallingThread` |
| Failed native startup was converted into successful unknown data | Every failed startup propagates to the repository's error snapshot | Failed-start regression includes NOT_READY, NATIVE_UNAVAILABLE and INVALID_REQUEST |
| Repository error snapshots made background work report success | Error snapshots are published and receive one quick WorkManager retry; persistent errors wait for the next normal period, avoiding unbounded backoff for signed-out accounts | Real WorkManager regression through the first and second ERROR attempts, then successful mock recovery |
| Mock success after an ERROR retained the old error flag | Successful mock responses explicitly clear the error flag, matching the native source | ERROR-to-success WorkManager regression |
| Overlapping manual/background refreshes could overwrite newer sparse data | One mutex protects the complete read/fetch/merge/write transaction; cancellation propagates | Controlled overlapping fetches, error timestamp preservation and cancellation regression |

The independent native-path reviewer identified the four refresh defects; the coordinator checked the call paths and implemented the fixes. The final independent diff review found no remaining actionable P1/P2 findings. No Rust dependency, JNI surface, credential storage, authentication endpoint, permission, or APK release gate was changed in this review.

## Local validation

Validation used a clean source export at a short local path to avoid the previously observed OneDrive/Gradle file-lock problem, JDK 17 and the repository's pinned Gradle dependencies.

- `testMockDebugUnitTest`: 37 tests, 0 failures.
- `testNativeDebugUnitTest`: 37 tests, 0 failures. These are JVM tests, not live native-device evidence.
- `lintMockDebug`: 0 errors; existing advisory warnings remain.
- `assembleMockDebug` and `assembleMockDebugAndroidTest`: pass.
- API 36 disposable, read-only emulator: mock install/launch passed; all three instrumentation classes below passed. The capped-retry regression was rerun after the final worker change and passed through both failures and subsequent recovery.
- Widget rendering covers 40×40, 56×72, 180×40, 40×180, 100×100 and 280×180dp across zero, partial, full, stale, error and unavailable scenarios. It checks bounds, accessible percentage and live-only green pixels, and writes deterministic mock PNGs for visual inspection.

Run the focused emulator checks after building/installing the mock and mock instrumentation APKs:

```text
adb shell am instrument -w -e class io.github.yunhyok.usagering.WidgetRenderingDeviceTest,io.github.yunhyok.usagering.UsageRefreshWorkerDeviceTest,io.github.yunhyok.usagering.StoredUsageRepositoryDeviceTest io.github.yunhyok.usagering.test/androidx.test.runner.AndroidJUnitRunner
```

The mock-only assumptions prevent these tests from mutating a signed-in native app. They are separate from the native physical-device acceptance tests.

## Remaining physical-device evidence

The user selected code and automated validation without connecting a physical phone for this review. Launcher drag/resize behavior on that phone, authenticated live reads and natural token-expiry refresh were not rerun. Existing app data, login and home-screen widgets were not touched.

The 2026-09-04 local follow-up observed `RATE_LIMITS_UNAVAILABLE` after successful native startup. Its exact cause remains unresolved: `native/src/runtime.rs` maps multiple typed App Server failures to that category. The pinned server already selects the Codex bucket for its legacy `rate_limits` field, so switching to another response field has no demonstrated justification. This review corrects error propagation, UI blocking and automatic retry; it does not claim that the historical live-service failure is fixed.

APK publication stays blocked under [the release checklist](RELEASE_CHECKLIST.md) until the exact candidate has the required reviewed physical evidence. Merging source changes is not a release-readiness claim.

## Sizing references

- [Android widget sizing metadata](https://developer.android.com/develop/ui/views/appwidgets/layouts): `targetCellWidth`/`targetCellHeight`, older-launcher dimensions and minimum resize dimensions.
- [Glance sizing modes](https://developer.android.com/develop/ui/compose/glance/build-ui): `SizeMode.Exact` and `LocalSize` for launcher-driven sizes.
