# ONNX Runtime's JNI entry points resolve Java class names at runtime.
-keep class ai.onnxruntime.** { *; }

# AndroidX WindowManager probes these optional device framework APIs at runtime.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# Preserve JS bridge methods for opt-in offline card script sandbox.
-keepattributes *Annotation*
-keepclassmembers class app.tavern.nativeclient.script.TavernHelperBridge {
    @android.webkit.JavascriptInterface <methods>;
}
