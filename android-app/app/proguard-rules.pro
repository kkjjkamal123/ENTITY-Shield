# ENTITY Shield release shrinking (R8). The ExecuTorch and fbjni AARs ship their own consumer rules;
# these are belt and braces for everything native code reaches by name.

# ExecuTorch: Module, Tensor, EValue, LlmModule and the LlmCallback interface are called from JNI.
-keep class org.pytorch.executorch.** { *; }
-keep interface org.pytorch.executorch.** { *; }
# fbjni / SoLoader: HybridData and the loader are reached by reflection from native code.
-keep class com.facebook.jni.** { *; }
-keep class com.facebook.soloader.** { *; }
-dontwarn com.facebook.**

# Our Explainer passes an LlmCallback implementation to native code: its methods keep their names.
-keepclassmembers class * implements org.pytorch.executorch.extension.llm.LlmCallback { *; }

# Stack traces in bug reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
