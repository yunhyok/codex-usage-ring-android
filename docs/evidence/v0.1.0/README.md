# `v0.1.0` evidence directory

This directory intentionally contains no passing evidence yet. Before a
release, add (in the release PR) the following reviewed files:

- `native-gate.json`: `{ "status": "pass", "commit": "...", "run_url": "..." }`
- `physical-device.json`: status, device model, Android API level, test date,
  reviewer, install/launch/widget/uninstall results, and a CI or lab run URL.

Do not add screenshots or logs containing account identifiers, serial numbers,
tokens, or signing material. The release workflow rejects missing files and
non-passing status values. This README is not evidence of a successful run.
