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
  Context before creating the client. No cleartext or verification bypass is
  introduced.
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

These patches are intentionally narrow and are applied through
`[patch.crates-io]` in `native/Cargo.toml`. Android TLS remains rustls with
system trust roots. The pinned app-server patch below additionally disables
plugin startup in the in-process facade.

## app-server 0.148.0 (pinned Codex commit)

* Upstream: `https://github.com/openai/codex.git` commit
  `3ba0f711642a888aec92a611a3f3b2211157ff89` (`rust-v0.148.0`), Apache-2.0.
* Vendored package: `patches/app-server`; `LICENSE` and `LICENSE-APACHE` are
  retained in that directory.
* `Cargo.toml`: upstream SHA-256
  `B485F6BD328CABE20630418AD6F51149813A5652AF2440BFD3C9B07E27E4C0C5`;
  patched SHA-256
  `DDA0D9D99CB84FBCD10D63A72757FC1677B44A8F7736F3D190DC3BF0C486650D`.
  The standalone manifest removes workspace-only metadata and pins Codex
  workspace dependencies to the exact commit for reproducible Cargo use.
* `src/in_process.rs`: upstream SHA-256
  `42F2996E2BBE0EDB233FC23AB1DC4095946BBDCF0505B8ADE600F8132521EB13`;
  patched SHA-256
  `08BF63958FBF499C538825794BB78EC19ABA76568B2F445ED4CF28BD28527F54`.
  The single source change forces `PluginStartupTasks::Skip`, so hostile
  plugin configuration cannot start plugin hosts in this facade.
* Patch file SHA-256:
  `patches/app-server-in-process-plugin-skip.patch` =
  `74A7A8529EB05DC117A6E06224FDFE68F498DB4B8A01D9D5352E18AB0C5693F3`.

The machine-readable provenance and hashes are in `upstream.toml`. The native
runtime marker includes the patch-file hash and the gate must compare it before
accepting a library as the pinned runtime.
