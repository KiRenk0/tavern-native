# ONNX Runtime's JNI entry points resolve Java class names at runtime.
-keep class ai.onnxruntime.** { *; }

# AndroidX WindowManager probes these optional device framework APIs at runtime.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
