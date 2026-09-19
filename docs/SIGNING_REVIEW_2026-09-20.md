# Release certificate approval — 2026-09-20

The repository owner explicitly approved this certificate and a replacement
for the former two-person review rule: **owner approval, independent AI
technical review, and coordinator verification**. The review actors are
identified below; this is not a claim of review by two humans.

## Approved public identity

- SHA-256: `e854068152eb91ff0589ac4962d56118854b3d797e553b35523ecd006c75ff05`.
- Public DER: [`release-certificate.cer`](../scripts/release/release-certificate.cer).
- Subject/issuer: `CN=Usage Ring Android Release, O=Yunhyok, C=KR`.
- Public key: RSA 4096 bits; certificate signature: SHA256withRSA.
- Validity: `2026-09-19T15:58:31Z` through `2056-09-19T15:58:31Z`.
- The certificate differs from the existing development signing identity.
  It cannot be used as an ordinary signature-compatible update of that app.

The independent `native_review` AI reviewer inspected only the public DER and
public metadata, independently verified the DER self-signature, checked the
digest/algorithm/validity, and found no P0–P3 issue. The coordinating agent
verified the exported certificate digest and the matching key restored from
the encrypted backup. The owner approved the stated identity and review
method after receiving that record. The approved policy pins these exact
public bytes; `scripts/release/test-supply-chain.ps1` rejects a missing or
mismatching DER file.

## Authorized key handling

The owner separately authorized the first-time local generation, protected
backup and GitHub registration. This is a one-time exception to the prior
local-key prohibition, not permission to generate replacement keys or sign
APKs locally. The key and password were generated outside the repository and
OneDrive. The PKCS12 is password-encrypted; the password is protected with
Windows DPAPI CurrentUser. ACLs permit only the current user and SYSTEM.
Passwords were passed through a child-process environment or redirected stdin,
never printed or placed in command-line values, Git or reviewer context.

The coordinator verified matching local backup bytes and a restored private
key signing/verifying a random challenge in memory. This did not sign an APK.
The backup is recoverable with the same Windows profile; it is **not an
independent disaster-recovery backup**. Before publication, copy the encrypted
PKCS12 to a separate trusted medium and keep its password in a separate secure
record. GitHub environment secrets are not a retrievable backup.

The four required signing secrets are registered in the GitHub `prerelease`
environment, restricted to the `v0.1.0` tag. No repository-wide signing secrets
were added. Registration and secret names were checked; actual production APK
signing has not yet run. The release workflow must still verify the APK signer
against the pinned SHA-256.

## Release boundary

Certificate approval does not mark the app release-ready. The exact final CI
candidate, complete physical-device matrix, fresh candidate-bound natural
auth-refresh evidence and external recovery backup remain required. Existing
phone login, app data and widgets remain preserved. No production APK or tag
was published as part of this approval.

References: [Android app signing](https://developer.android.com/studio/publish/app-signing),
[keytool password modifiers](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html#common-command-options),
[GitHub environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
