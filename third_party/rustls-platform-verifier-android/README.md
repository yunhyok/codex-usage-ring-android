# Vendored Android platform verifier

This source tree is derived exactly from rustls-platform-verifier v0.7.0 at
commit `996b1c903491641b17b3c9afb65d1352f6fc6b76`.

The Kotlin verifier preserves Android's system `TrustManager`, certificate
usage checks, hostname-verification boundary, and JNI package/signature. The
local security adaptations are limited to the vendored source. The production
TrustManager is initialized with a null keystore so Android applies the app's
Network Security Configuration and its system-only trust anchors. A directly
supplied `AndroidCAStore` would also trust user-installed CAs and bypass that
configuration. The loaded store is retained only for the subsequent PKIX
revocation check, after the configured TrustManager accepts the chain. The PKIX
revocation checker retains `SOFT_FAIL` and `ONLY_END_ENTITY`, and adds
`PREFER_CRLS` only when no stapled OCSP response is supplied. OCSP/CRL
fallback remains enabled and `NO_FALLBACK` is omitted; no user/raw trust
anchors, pins, or revocation bypass are introduced. The Android trust-anchor
collision scan also advances its alias index for deleted or malformed anchors,
so a sparse system store cannot cause an infinite loop during verification.

The native Android flavor compiles this visible source directly. The mock
application does not include this source set; its instrumentation APK includes
the same sources for isolated trust-policy regression tests. No Cargo-generated
Android artifact is resolved or staged. `BuildConfig.java` is a release-only shim for
the upstream test hooks (`TEST = false`).

Upstream source URL:

<https://raw.githubusercontent.com/rustls/rustls-platform-verifier/996b1c903491641b17b3c9afb65d1352f6fc6b76/android/rustls-platform-verifier/src/main/java/org/rustls/platformverifier/CertificateVerifier.kt>

License texts are retained in `LICENSE-APACHE` and `LICENSE-MIT`.

Upstream `CertificateVerifier.kt` SHA-256:
`3d6f8593cdb87af14265ebd28e20c471adbd3d7c8766b3ee543b85e92e928561`.
Modified vendored file SHA-256 (including the application trust policy,
conditional CRL preference, provenance header, and collision-scan guard):
`0a345c5237f7d6c9d11f2709c520a4969b4753f22e3f3e134f8248ab80224920`.

Vendored `BuildConfig.java` SHA-256 (the release-only `TEST = false` shim):
`92f6fbc924c2675f33e67cf9089b9e75f21610439e785e06b6eae735361d7b8f`.

The [2026-09-20 independent review](../../docs/TRUST_POLICY_REVIEW_2026-09-20.md)
records the source/provenance approval and its runtime evidence boundary.
