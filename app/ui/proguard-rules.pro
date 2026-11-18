# VeriSpec-AI release rules. Evidence wire DTOs are reflection-free (kotlinx.serialization
# generates serializers at compile time), so only the native runtimes need keeps.
-keep class org.tensorflow.lite.** { *; }
-keep class org.pytorch.executorch.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class ai.verispec.data.runtime.llamacpp.LlamaNative { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn org.pytorch.**
-dontwarn com.google.mediapipe.**
