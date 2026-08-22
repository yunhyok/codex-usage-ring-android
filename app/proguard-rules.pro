# JNI and rustls-platform-verifier-android are called reflectively from Rust.
-keep class io.github.yunhyok.usagering.data.NativeCodexBridgeNative { *; }
-keep,includedescriptorclasses class org.rustls.platformverifier.** { *; }
# Keep the exact private @JvmStatic JNI entry point and its return DTO/signature.
# The broad package rule above is retained as defense against Kotlin synthetic
# accessors and the verifier's private result fields.
-keep class org.rustls.platformverifier.CertificateVerifier {
    *;
}
-keep class org.rustls.platformverifier.VerificationResult { *; }
-keep class org.rustls.platformverifier.BuildConfig { *; }
