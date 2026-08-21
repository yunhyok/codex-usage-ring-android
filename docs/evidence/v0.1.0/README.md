# `v0.1.0` evidence directory

This directory intentionally contains no passing evidence yet. Before a
release, add (in the release PR) the following reviewed files:

- `native-gate.json`: `{ "status": "pass", "commit": "...", "run_url": "..." }`
- `physical-device.json`: status, device model, Android API level, test date,
  reviewer, HTTPS run URL, the exact 40-character source commit, and the
  SHA-256 of the exact signed APK exercised on the device. Every check below must be the literal
  string `pass`:

  - `install`, `launch`, `native_load`, and `tls_system_trust`
  - `device_code_login`, `restart_token_refresh`, and `process_recovery`
  - `rate_limits_read` and `refresh_25`
  - `widget_add_resize` and `notification_dismiss_restore`
  - `offline_recovery`, `logout_relogin`, and `reboot_recovery`
  - `secret_log_scan`, `plugin_mcp_blocked`, and `uninstall`

The source commit must equal the tagged commit. `apk_sha256` identifies the
exact APK exercised on the device (typically the nativeDebug candidate); the
release workflow computes and publishes the final signed APK digest separately
after rebuilding from that same tagged source. The record must not contain a device serial, account identifier, device code,
verification URL query string, token, or raw log output. A `pass` value needs
reviewable local evidence; editing the JSON alone does not satisfy the gate.
Start from [`physical-device.template.json`](physical-device.template.json)
only after the local preflight script has confirmed the exact ARM64 APK hash.

Do not add screenshots or logs containing account identifiers, serial numbers,
tokens, or signing material. The release workflow rejects missing files and
non-passing status values. This README is not evidence of a successful run.
