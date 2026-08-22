# Android Studio validation

Android Studio is the primary development and inspection environment. Command
line tools are used only to automate builds and collect reproducible emulator
evidence; Android Studio uses the same ADB transport underneath.

## Pinned project toolchain

| Component | Pin |
| --- | --- |
| Android Studio | Quail 3 / installed build `AI-261.26222.65.2613.15948027` |
| Project JDK | Eclipse Temurin 17.0.20+8 |
| Android Gradle Plugin | 9.1.1 |
| Gradle wrapper | 9.3.1 |
| compile/target SDK | 36 |
| min SDK | 29 |
| Android NDK | 28.2.13676358 |
| Rust | 1.95.0 with `aarch64-linux-android` target |

Android Studio itself uses its bundled JetBrains Runtime; Gradle is configured
and verified against JDK 17. Do not replace the wrapper or native pins during a
release build without a reviewed dependency change.

## Interactive IDE evidence (2026-08-21)

- Android Studio Quail 3 opened the repository as the `CodexUsageRing` Android
  project and discovered the `app` module and the configured API 35 AVD.
- The project-local `#GRADLE_LOCAL_JAVA_HOME` mapping points to Eclipse Temurin
  `17.0.20+8`. Android Studio's `idea.log` records Gradle using that exact JDK,
  followed by `onSuccess(RESOLVE_PROJECT:0)` and a completed 43.214-second sync.
- The IDE's optional Microsoft Defender exclusion remained disabled. No Windows
  security exclusions were added for the project or Android Studio.
- Quail's optional Gradle daemon-toolchain migration was not accepted because
  the repository does not configure automatic JDK download repositories.

## Device Manager matrix

The following x86_64 AVDs cover platform behavior in `mockDebug`:

| AVD | Focus |
| --- | --- |
| `UsageRing_API29` | minimum supported Android 10 behavior |
| `UsageRing_API31` | Android 12 widget and background restrictions |
| `UsageRing_API33` | notification runtime permission |
| `UsageRing_API34` | dismissible ongoing notification behavior |
| `Medium_Phone_API_35` | Android 15 regression check |
| `UsageRing_API36` | target/current behavior |

Use Compose Preview and Layout Inspector for layout work, then validate the
installed widget at compact and expanded sizes in both light and dark mode.
Profiler and App Inspection checks should confirm bounded ring bitmaps, one
unique periodic WorkManager request, and no unexpected network traffic in the
mock flavor.

## Local emulator evidence (2026-08-21)

- API 29: `mockDebug` streamed install passed and a forced-stop cold launch of
  `MainActivity` completed successfully. The dashboard rendered the 5-hour and
  7-day values, reset times, selected 25% ring, and refresh control without
  clipping at the minimum supported API.
- API 31, 33, 34, and 35: each dedicated x86_64 AVD booted, installed the
  current `mockDebug` APK, and launched `MainActivity`. Captured logcat output
  contained no app fatal exception or ANR. The API 34 AVD required a second,
  staged boot attempt after its first boot stopped before installation; the
  rerun completed successfully.
- API 36: `mockDebug` install/launch passed. The launcher accepted the pinned
  widget, `dumpsys appwidget` recorded the provider and bitmap-backed
  `RemoteViews`, and resizing exercised both the compact vertical-percent and
  wide horizontal-percent layouts. A dark translucent widget surface was
  added after the wide layout exposed a wallpaper-contrast issue.
- API 36 notification: the Android 13+ permission dialog was accepted in the
  AVD. The notification manager recorded importance `LOW`, no sound, no
  vibration, no badge, `ONGOING_EVENT`, and no foreground service. The panel
  rendered the exact `25% · 7-day` value and a white 25% horizontal bar.
- The mock scenario and snapshot are persisted in app-private preferences so
  process recreation does not turn a previously rendered widget into an
  unknown value.

Screenshots, UI hierarchies, and `dumpsys` output are generated below
`app/build/reports/ui/` and intentionally excluded from source control. They
are local development evidence, not a substitute for the required ARM64
physical-device record.

## Physical ARM64 gate

Only a personal ARM64 Android 10+ phone connected through Android Studio Device
Manager may satisfy the native gate. Prefer Wireless Debugging on Android 11+
and USB debugging on Android 10. Record device model/API, install, native-load,
device-code login, restart/token refresh, 25 refreshes, widget resize,
notification dismissal/restoration, offline recovery, logout/re-login, reboot,
and uninstall. Sanitize serial numbers, account identifiers, codes, and logs
before committing evidence.

### Local API 36 progress (2026-08-22)

The current patched ARM64 candidate identified by SHA-256
`c5ecae5f39d7b5076e5c21ff61ad32a8e136a8591d93291b8b21f57df1fcafcc`
passed data-preserving install, launch, native load, fresh logout/re-login through
the system-trust device-code flow, process recreation, one authenticated
rate-limit read, and 25 sequential authenticated refreshes. The immediately
preceding candidate also passed notification cancellation/republication,
offline cache preservation and online recovery, and user-performed widget
add/resize. The patched candidate preserved that widget binding after update.
A read-only WorkManager check confirmed boot restoration is enabled with
exactly one active unique refresh job. A physical reboot followed by unlock
also preserved authentication, the bound widget, that unique work item, and one
live rate-limit refresh. A separate runtime-policy instrumentation test
confirmed the exact six-method JNI surface, disabled telemetry, plugin, MCP,
and shell capabilities, the exact `c.pki.goog` cleartext CRL exception with an
unrelated host denied, and that explicit caller-forced auth refresh is not
exposed through JNI.

In the pinned `rust-v0.148.0` source, regular managed ChatGPT auth calls the
internal proactive-refresh path from `AuthManager::auth()`. A parseable access
token is eligible when its `exp` is within five minutes; the fallback for a
missing/unusable `exp` is an eight-day `last_refresh` age. API-key, PAT, external
bearer, and other auth modes do not use this trigger. The relevant pinned source
is `codex-rs/login/src/auth/manager.rs` (`auth`,
`should_refresh_proactively`, and `refresh_token`) and
`codex-rs/login/src/token_data.rs`; its revision is bound by
`third_party/openai-codex/upstream.toml`.

The instrumentation assertions use fixed messages and do not write actual
usage values, account identity, pairing material, app-private paths, raw JNI
responses, logcat, screenshots, or UI hierarchies. Temporary notification
permission and network changes were restored, and each test-only package was
removed without uninstalling or clearing the target app. Sanitized local JSON
records are under
`app/build/reports/local-native-login-nsc-v5-device-api36/` and
`app/build/reports/local-native-relogin-patch-v3-device-api36/` and remain
gitignored. Native CI compiles and uploads the corresponding instrumentation APK
without running it; execution remains restricted to the reviewed physical-device
workflow.

The physical-only test classes are:

- `NativeRateLimitsDeviceTest`: 25 sequential authenticated reads and value
  invariants.
- `NativeNotificationDeviceTest`: quiet ongoing-channel policy and
  cancel/repost behavior.
- `NativeConnectivityDeviceTest`: offline cache preservation and online
  recovery, run as separately selected methods around externally restored
  network state.
- `NativePolicyDeviceTest`: in-process disabled-capability metadata, exact JNI
  allowlist, and runtime cleartext policy restricted to the CRL distribution
  host.
- `NativeWorkSchedulerDeviceTest`: read-only boot-restore and unique periodic
  work state.
- `NativeWidgetDeviceTest`: read-only launcher binding, current resize-option
  invariants, horizontal/vertical resize policy, and home-screen category.
- `NativeRebootRecoveryDeviceTest`: one post-reboot authenticated refresh plus
  preserved unique work and widget binding.

This is a partial local result. Actual token-expiry refresh, uninstall, reviewer
identity, and CI-commit binding are still pending. Successful authenticated
reads prove the request path but do not prove that a token actually expired and
refreshed: `AuthManager::auth()` may return the old auth after a failed refresh,
and `account/rateLimits/read` exposes no refresh marker. The pinned source also
defines no fixed provider token lifetime, so an arbitrary clock wait cannot
satisfy this gate. It remains pending without inspecting or manipulating secret
auth material or adding a caller-forced refresh JNI. The partial result cannot
populate the release
`physical-device.json` or authorize an APK release.

Until that evidence and the Codex runtime cross-build are both `pass`, the
repository may publish source and mock artifacts in CI, but must not publish a
signed APK or create the `v0.1.0` tag.
