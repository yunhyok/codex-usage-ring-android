# Android portability and in-process hardening patches

The source in `patches/` is vendored from the crates.io packages selected by
the pinned `rust-v0.148.0` dependency graph. Each package retains its upstream
license and notice files. The app-server source is vendored from the pinned
Codex repository commit and has a separate Apache-2.0 notice in
`upstream.toml`; all modified-file hashes and exact changes are recorded there.

## reqwest 0.12.28

* Upstream package: `reqwest` 0.12.28, MIT OR Apache-2.0.
* Upstream `Cargo.toml` SHA-256: `23C8A0827AAAC74059965A182C6873990E75AA550E8079D3B51BB95092871205`.
* Patched `Cargo.toml` SHA-256: `B499D015BA57FBB3B3C8BFC43BEEF040B8E37979901AA2EA7026A4FB53F67A66`.
* Exact change: remove `default-tls` from the default feature list. The
  pinned Codex graph explicitly enables reqwest's rustls client; this prevents
  the unused native-tls/openssl default from entering Android.
* Android-only change: add `rustls-platform-verifier` 0.7.0 and route the
  verified rustls builder through Android's platform TrustManager when no
  caller-supplied custom roots are configured. This intentionally changes the
  certificate-root implementation while retaining certificate verification;
  the JNI start method must call
  `rustls_platform_verifier::android::init_with_env` with the application
  Context before creating the client. This Rust patch introduces no transport
  verification bypass. The Android application separately permits cleartext
  only to the exact public CRL distribution host `c.pki.goog`; its scope and
  fail-closed review rule are documented in
  [`docs/THREAT_MODEL.md`](../../docs/THREAT_MODEL.md).
* Upstream `src/async_impl/client.rs` SHA-256:
  `8C30B009838BE2126B90344EF97FB6141AC8BDF695F4E7BB00A3052A707D9B5C`.
* Patched `src/async_impl/client.rs` SHA-256:
  `3798B0C8FE955D75A8D636135AC6947659D991C78555C5E27823F8BA54011CAB`.
  The Android-only branch uses `rustls-platform-verifier` with the Java
  TrustManager when no custom roots are supplied; the non-Android branch is
  unchanged.

## sentry 0.46.2

* Upstream package: `sentry` 0.46.2, MIT.
* Upstream `Cargo.toml` SHA-256: `F2D8ADB95CE6E0DA6ABB01A8DE5B2D2A757AEF63AC65036E8589D8292F4B9297`.
* Patched `Cargo.toml` SHA-256: `BE73DC4ED0198DE96D8BE705E5D48481BECB521DA3D04B4E421E357B800A6C93`.
* Exact change: remove the default `transport` feature. Codex's feedback
  layer remains available, but the unused Sentry HTTP transport (and its
  native-tls/openssl dependency) is not linked into this Android library.

These patches are intentionally narrow and are applied through the exact
`[patch.crates-io]` and `[patch."https://github.com/openai/codex.git"]`
entries in `native/Cargo.toml`. Android TLS remains rustls with system trust
roots. The pinned app-server patch below additionally disables plugin startup
in the in-process facade.

## codex-git-utils 0.148.0 (pinned Codex commit)

* Upstream: `https://github.com/openai/codex.git` commit
  `3ba0f711642a888aec92a611a3f3b2211157ff89` (`rust-v0.148.0`), Apache-2.0.
* Vendored package: `patches/codex-git-utils`; source files are unchanged and
  the Apache-2.0 license is retained.
* `Cargo.toml`: upstream SHA-256
  `1B812BBA7CC7DFA5339AB3DDB5E499E35FCAFE42A6489E1E7A43208B291A816D`;
  patched SHA-256
  `44C57496572F5E75382398A7D2BDD9D7898B28E4043917B71AD7CDD0ED0F279A`.
* Exact change: replace workspace-only dependency declarations with explicit
  versions pinned by `native/Cargo.lock`, and select `gix` 0.83.0. This moves
  the Android target to `gix` 0.83.0, `gix-fs` 0.21.2, and `gix-pack` 0.70.0,
  removing the high-severity ranges reported for the former 0.81.0/0.19.2/
  0.68.0 graph. No Git operation is exposed through JNI.
* The 14 files under `src/` are byte-identical to the pinned upstream tree.
  Their canonical path-sorted source-tree SHA-256 is
  `612B653C5725B1285076A9F8A27461F16464D69CC97A82458C1B790B1CEFFA15`;
  the native gate and exported runtime marker both bind this digest.

## rama-dns 0.3.0-alpha.4

* Upstream package: `rama-dns` 0.3.0-alpha.4 from
  `https://github.com/plabayo/rama` commit
  `4733273a10a791762e2b7727850032b0c9b8536d`, MIT OR Apache-2.0.
* Vendored package: `patches/rama-dns-0.3.0-alpha.4`; both upstream license
  texts are retained.
* `Cargo.toml`: upstream SHA-256
  `26AF0AAAB138F30324BC7AAB39E083F06D1B41C29CC7716DC5EB7B81569B6B16`;
  patched SHA-256
  `7AC309C4323860CBC77C37EA0CD82AAA62D3716B6E8312B7CA7C4CCE5C40D4A7`.
  The only dependency change selects `hickory-resolver` 0.26.1.
* `src/hickory.rs`: upstream SHA-256
  `78C03B179AA11462C3F0DC233B60800DBA313DA96610404D5498CF9B76BC2C0F`;
  patched SHA-256
  `9518B743ADDDBC5A0D54B7587F9D712CF575FE8FAD23959C78CA1475016DABF9`.
  The source-only API adaptation uses Hickory 0.26's public server groups,
  `TokioRuntimeProvider`, fallible resolver construction, and typed answer
  records. Resolver behavior and Rama's public API are otherwise unchanged.
* The resulting Android graph contains `hickory-proto` and
  `hickory-resolver` 0.26.1, outside the high-severity 0.25.2 range.

## app-server 0.148.0 (pinned Codex commit)

* Upstream: `https://github.com/openai/codex.git` commit
  `3ba0f711642a888aec92a611a3f3b2211157ff89` (`rust-v0.148.0`), Apache-2.0.
* Vendored package: `patches/app-server`; `LICENSE` and `LICENSE-APACHE` are
  retained in that directory.
* The complete patched build-input tree contains 231 files and has canonical
  SHA-256 digest
  `3BD07B5DF17EF9AF4DDA7CAE82B1DE75F2C3F25E8E52FB4323B2AAB7172A9A86`.
  The current vendored `Cargo.toml` digest is
  `DDA0D9D99CB84FBCD10D63A72757FC1677B44A8F7736F3D190DC3BF0C486650D`.
  The native gate binds both values into the runtime marker.
* `Cargo.toml`: upstream SHA-256
  `B485F6BD328CABE20630418AD6F51149813A5652AF2440BFD3C9B07E27E4C0C5`;
  patched SHA-256
  `DDA0D9D99CB84FBCD10D63A72757FC1677B44A8F7736F3D190DC3BF0C486650D`.
  The standalone manifest removes workspace-only metadata and pins Codex
  workspace dependencies to the exact commit for reproducible Cargo use.
* `src/in_process.rs`: upstream SHA-256
  `42F2996E2BBE0EDB233FC23AB1DC4095946BBDCF0505B8ADE600F8132521EB13`;
  patched SHA-256
  `89250F5EF5DC3501D9BC5768AC6F43477BA36CB2C3E48EEFB23C80FF51C4FEDE`.
  One change forces `PluginStartupTasks::Skip`, so hostile plugin
  configuration cannot start plugin hosts in this facade. The Android-only
  installation-ID resolver avoids relying on the desktop advisory-lock
  behavior that is not portable to every Android app-private filesystem. It
  reuses a valid UUID and otherwise writes a UUIDv7 through a same-directory
  temporary file and atomic rename; non-Android builds continue to call the
  upstream resolver. Android behavior remains part of the physical-device
  acceptance gate.
* Plugin startup patch SHA-256:
  `patches/app-server-in-process-plugin-skip.patch` =
  `31C11D95092364BA25D5748390252211B2CEB6FA8444D8FF6BFE81C48BC8D572`.
* Android installation-ID patch SHA-256:
  `patches/app-server-in-process-android-installation-id.patch` =
  `3922B9110AEE8A3E7326C3C0CE8DD3FF36881802D9DFA9290AC85E9DA789B8F6`.
* Device-login error-category patch SHA-256:
  `patches/app-server-device-login-error-category.patch` =
  `14B1B07F07912F0178BD37DBC6D49A8001F9E76CB333ABB524236B73CBFA5714`.
  It changes `src/error_code.rs` from upstream SHA-256
  `5EFBEBCDB63C55E5BF71DB70AA31F9A1C60AE88AF7DD9E799B6B659D8B34DDBE`
  to `74AB810D12A116928E5C6AF69E36E80D0090BE6107FBADC3F30B2EE07905636C`,
  and `src/request_processors/account_processor.rs` from upstream SHA-256
  `737B82796346B0011F71FD096690635C17FA80A7E60A9CDD83BA1F6A4053B6FF`
  to `0A90C7329E1AB1BF1E97A3556DEAB80FBBCACA4ECDA961EF3198E2832E3B3B94`.
  The patch converts provider/transport failure details to one allowlisted
  `usageRingCategory` value with a generic JSON-RPC message. Raw URLs,
  identifiers, tokens, and response bodies remain inside the app-server.
  All three patches pass `git apply --check` against commit
  `3ba0f711642a888aec92a611a3f3b2211157ff89`.

The machine-readable provenance and hashes are in `upstream.toml`. The native
runtime marker includes all patch-file hashes and resulting modified-source
hashes; the gate must compare them before accepting a library as the pinned
runtime.
