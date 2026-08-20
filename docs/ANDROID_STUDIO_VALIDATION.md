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

Until that evidence and the Codex runtime cross-build are both `pass`, the
repository may publish source and mock artifacts in CI, but must not publish a
signed APK or create the `v0.1.0` tag.
