# Rules for an app that minifies and depends on this library. The app does not minify today;
# these make sure that switching it on cannot break native code, which finds everything below
# by name (llama/src/main/cpp/proseplow_jni.cpp, JNI_OnLoad).

# The native methods are registered from a table of names and signatures.
-keep class us.theworkmans.proseplow.llama.LlamaJni { *; }

# Constructed by native code to report a failure: NativeLlamaException(int, String).
-keep class us.theworkmans.proseplow.core.engine.NativeLlamaException {
    <init>(int, java.lang.String);
}

# Called back by native code during a load and an evaluation. Keeping the interface methods
# keeps the names of every implementation's overrides with them.
-keep interface us.theworkmans.proseplow.core.engine.EvaluationObserver { *; }
-keep interface us.theworkmans.proseplow.core.engine.LoadProgress { *; }
