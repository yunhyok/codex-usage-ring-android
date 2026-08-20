# Contributing to Usage Ring

Contributions are welcome through a reviewed pull request.

## Development rules

1. Use Android Studio Quail 3 and JDK 17 (or document an equivalent setup).
2. Keep the `mockDebug` path deterministic and separate from native/release
   behavior. A mock test does not waive the native gate.
3. Keep permissions, exported components, data retention, and network behavior
   explicit in the pull request. Update `docs/THREAT_MODEL.md` when they
   change.
4. Do not commit signing keys, credentials, device serials, generated local
   configuration, or private endpoint/model settings.
5. Include tests and meaningful validation output. If a check cannot run,
   state why and mark it as not run rather than implying success.

## Pull request checklist

- [ ] Scope and user-visible behavior are described.
- [ ] `test`, `lint`, and `mockDebug` pass locally or the limitation is noted.
- [ ] Rust format, clippy, and tests pass for native changes.
- [ ] Permissions and privacy impact are reviewed.
- [ ] Documentation and release evidence requirements are updated as needed.
- [ ] No generated secrets, keys, or unrelated files are included.

By contributing, you agree that your work is provided under the Apache License
2.0 and that you will follow the Code of Conduct.
