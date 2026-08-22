# Security policy

## Supported versions

Only the latest `v0.1.x` pre-release line is expected to receive security
fixes. Development snapshots are not supported release artifacts.

## Reporting a vulnerability

Please do not open a public issue for a suspected vulnerability. Use the
repository's private security-advisory channel when enabled, or contact the
maintainer through the private project owner account with:

- a concise impact summary and affected version/commit;
- reproduction steps or a minimal proof of concept;
- logs or screenshots with tokens, serial numbers, and personal data removed;
- any suggested mitigation.

If no private channel is configured, ask the maintainer for one before sharing
details. We will acknowledge receipt, coordinate a fix, and publish a concise
advisory when disclosure is appropriate. Do not send passwords, signing keys,
one-time codes, or complete user data.

## Supply-chain and privacy notes

Keep signing credentials, API tokens, local endpoint/model settings, and device
identifiers out of Git. Report unexpected permissions, network behavior,
unsigned/debug release artifacts, and native memory-safety issues even if they
do not yet have a CVE.

## Dependency-review gate

Pull requests run GitHub's dependency-review action with `fail-on-severity:
high`. High and critical advisories are release blockers; this repository does
not maintain a GHSA allowlist. An advisory can be considered resolved only when
the fixed version is present in the exact Android target graph and the final
native artifact no longer contains the vulnerable package. Runtime claims such
as "this API is not called" are not an exception.

For a proposed compile-time-unreachable exception, attach all of the following
to the same commit: `cargo tree --locked --target aarch64-linux-android -i
<package>` showing no path, and an inspection of the produced ARM64 `.so`
showing no package code or data. Until both checks pass, the advisory must be
fixed (or the affected dependency removed) and the release remains blocked.

### Reviewed native dependency state (2026-08-21)

The Codex `rust-v0.148.0` lock originally selected vulnerable gix and Hickory
lines. This repository keeps the Codex commit fixed while applying two
hash-bound, source-visible parent-package adaptations:

- `gix` 0.83.0, `gix-fs` 0.21.2, and `gix-pack` 0.70.0 replace the former
  0.81.0/0.19.2/0.68.0 graph. These versions are outside the ranges in
  [GHSA-p3hw-mv63-rf9w](https://github.com/advisories/GHSA-p3hw-mv63-rf9w),
  [GHSA-f26g-jm89-4g65](https://github.com/advisories/GHSA-f26g-jm89-4g65),
  [GHSA-pg4w-g64p-qwhj](https://github.com/advisories/GHSA-pg4w-g64p-qwhj),
  [GHSA-fr8x-3vfx-f45h](https://github.com/advisories/GHSA-fr8x-3vfx-f45h),
  [GHSA-f89h-2fjh-2r9q](https://github.com/advisories/GHSA-f89h-2fjh-2r9q),
  and [GHSA-x494-mj8g-cj27](https://github.com/advisories/GHSA-x494-mj8g-cj27).
- `hickory-proto` and `hickory-resolver` 0.26.1 replace 0.25.2, outside
  [GHSA-3v94-mw7p-v465](https://github.com/advisories/GHSA-3v94-mw7p-v465)
  and [GHSA-q2qq-hmj6-3wpp](https://github.com/advisories/GHSA-q2qq-hmj6-3wpp).

Three moderate advisories remain visible rather than being described as fixed:
`jsonwebtoken` 9.3.1
([GHSA-h395-gr6q-cpjc](https://github.com/advisories/GHSA-h395-gr6q-cpjc)),
`opentelemetry_sdk` 0.31.0
([GHSA-w9wp-h8wv-79jx](https://github.com/advisories/GHSA-w9wp-h8wv-79jx)),
and `tar` 0.4.45
([GHSA-3pv8-6f4r-ffg2](https://github.com/advisories/GHSA-3pv8-6f4r-ffg2)).
They are not allowlisted: the PR gate still reports them below its High release
threshold. The six-method JNI surface does not expose JWT transport decoding,
inbound telemetry baggage, or archive/plugin operations; OTEL exporters and
plugin startup are also forced off. These controls reduce current exposure but
do not remove the packages, so every Codex pin update must re-evaluate them.
