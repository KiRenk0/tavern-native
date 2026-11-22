# ONNX Runtime's JNI entry points resolve Java class names at runtime.
-keep class ai.onnxruntime.** { *; }

# AndroidX WindowManager probes these optional device framework APIs at runtime.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# Handlebars renders and compiles templates in Java. Its optional JS helper registration
# and JavaScript precompiler are unused and Android does not ship JSR 223.
-dontwarn javax.script.Bindings
-dontwarn javax.script.ScriptContext
-dontwarn javax.script.ScriptEngine
-dontwarn javax.script.ScriptEngineManager

# Preserve JS bridge methods for opt-in offline card script sandbox.
-keepattributes *Annotation*
-keepclassmembers class app.tavern.nativeclient.script.TavernHelperBridge {
    @android.webkit.JavascriptInterface <methods>;
}
