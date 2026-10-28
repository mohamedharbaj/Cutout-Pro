# ---- Cutout Pro ----

# ONNX Runtime calls Java classes/fields from native (JNI) code by name; R8 must not rename or remove them.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Any native method must keep its name so JNI can find it.
-keepclasseswithmembernames class * {
    native <methods>;
}

# OkHttp optional TLS providers (not present on Android by default).
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
