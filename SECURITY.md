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
