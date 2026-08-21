# JNI and rustls-platform-verifier-android are called reflectively from Rust.
-keep class io.github.yunhyok.usagering.data.NativeCodexBridgeNative { *; }
-keep,includedescriptorclasses class org.rustls.platformverifier.** { *; }
