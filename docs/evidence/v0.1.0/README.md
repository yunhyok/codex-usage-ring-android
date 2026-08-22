# `v0.1.0` evidence directory

This directory intentionally contains no passing evidence yet. Before a
release, add (in the release PR) the following reviewed files:

- `native-gate.json`: `{ "status": "pass", "candidate_commit": "...", "run_url": "..." }`
- `physical-device.json`: status, device model, Android API level (integer >=29),
  ABI (`arm64-v8a`), test date, reviewer, the exact repository Actions run URL,
  the candidate commit and CI run URL, and SHA-256 values for the exact debug
  APK, unsigned native release APK, instrumentation APK, and packaged native
  library. It must additionally record the hashes of the locally test-signed
  release and instrumentation APKs (`tested_release_apk_sha256`,
  `tested_instrumentation_apk_sha256`) and the test certificate
  (`test_signing_certificate_sha256`). The candidate run URL must identify a
  successful CI run whose `head_sha` equals `candidate_commit`. Every check below must be the literal
  string `pass`:

  - `install`, `launch`, `native_release_install`,
    `native_release_instrumentation`, `native_load`, and `tls_system_trust`
  - `device_code_login`, `restart_token_refresh`, and `process_recovery`
  - `rate_limits_read` and `refresh_25`
  - `widget_add_resize` and `notification_dismiss_restore`
  - `offline_recovery`, `logout_relogin`, and `reboot_recovery`
  - `secret_log_scan`, `plugin_mcp_blocked`, and `uninstall`

The release tag may be an evidence-only descendant of `candidate_commit`, but
the release workflow verifies ancestry and permits only the two evidence JSON
files in the candidate-to-tag diff. `apk_sha256` identifies the exact **native
debug** APK used for development smoke testing and must match the candidate CI
artifact. That run is development evidence only; it cannot satisfy
`native_release_install` or `native_release_instrumentation`.

The release payload proof is a separate, human-reviewed test on the exact
unsigned `nativeRelease` APK downloaded from that same CI run:

1. Verify `native_release_apk_sha256`, `raw_native_library_sha256`, and
   `packaged_native_library_sha256` against the downloaded candidate artifact.
   The raw hash is the hard-gate `libusage_ring_codex.so`; the packaged hash is
   the deterministic AGP/NDK-stripped payload embedded in both APKs. They are
   expected to differ when debug sections are removed.
2. Make a disposable copy and invoke Android SDK `apksigner sign` on that copy
   only. Do not run Gradle, rebuild native code, run `zipalign`, edit the APK,
   or repackage it. The field `release_payload_derivation` must be exactly
   `apksigner-sign-only`.
3. Sign the candidate instrumentation APK with the same local test
   certificate, again using `apksigner` only. Verify both certificates with
   `apksigner verify --print-certs`; record the normalized certificate digest in
   `test_signing_certificate_sha256` and the resulting file hashes in
   `tested_release_apk_sha256` and `tested_instrumentation_apk_sha256`.
4. Install the test-signed release copy on the physical ARM64 device, run the
   native/runtime checks and the instrumentation suite from Android Studio,
   and record `native_release_install: pass` and
   `native_release_instrumentation: pass`. The signed copies are not the final
   production-signed APK and must not be committed or uploaded.

The read-only helper
[`scripts/device/verify-test-signed-payload.ps1`](../../../scripts/device/verify-test-signed-payload.ps1)
can be run before the device session to compare all non-signature ZIP payload
entries, verify both test-signed certificates, and emit the three signed-file
hashes plus the two unsigned candidate hashes. It does not create a keystore,
invoke ADB, install an APK, or rebuild anything; its `status: pass` is only a
static input check and is not a substitute for the Android Studio
install/instrumentation evidence.

The unsigned release APK and instrumentation APK hashes must also match that
same CI artifact. The raw native-library hash must match the gate artifact, and
the packaged native-library hash must match the independently verified strip
derivation and both APK entries. The release workflow signs that exact
unsigned payload for publication; it does not rebuild app or native code. A
disposable local test keystore may be created only outside the repository for
this review; it must never be treated as or derived from the production key,
committed, uploaded, or retained after review. The record
must not contain a device serial, account identifier, device code, verification
URL query string, token, or raw log output. A `pass` value needs reviewable
local evidence; editing the JSON alone does not satisfy the gate. Start from
[`physical-device.template.json`](physical-device.template.json) only after the
local preflight script has confirmed the exact ARM64 APK hashes.

Do not add screenshots or logs containing account identifiers, serial numbers,
tokens, passwords, keystore paths, or signing material. The release workflow
rejects missing files and non-passing status values, including either of the
two nativeRelease proof statuses. This README is not evidence of a successful
run.

## Natural refresh evidence protocol

`NativeAuthRefreshEvidenceDeviceTest` is the only supported natural-refresh
evidence check. It is skipped by ordinary connected CI unless the Android
instrumentation arguments below are explicitly enabled:

```text
usageRingNaturalRefreshEvidence=true
baselineObservationCount=<nonnegative decimal count>
notBeforeEpochMillis=<positive decimal epoch-millisecond boundary>
```

Capture the baseline count and local `notBeforeEpochMillis` boundary from the
non-secret `authRefreshEvidence()` read immediately after installing the exact
candidate. Wait for managed ChatGPT auth to reach its normal proactive-refresh
condition, without editing auth material or invoking a caller-forced refresh.
Then run one ordinary WorkManager/repository refresh with those arguments. The
test passes only when the persisted observation count is newly greater than
the supplied baseline and its observation time is not before the supplied
boundary. Repeated ordinary reads cannot force token expiry or refresh. The
physical evidence JSON must contain only pass/fail status and sanitized run
metadata; never record counts, timestamps, usage, account identifiers, URLs,
codes, tokens, or raw output.
