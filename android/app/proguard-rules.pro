# Conjunction R8 rules. Most libraries ship consumer rules; these cover reflection the app relies on.

# MediaPipe Tasks: native code calls back into these Java classes by name.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ML Kit GenAI (AICore binder interfaces).
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**

# Firebase Functions callable results are plain Map/List; Firestore reads use DocumentSnapshot
# getters (no POJO mapping), so no model keep rules are needed.

# Stack traces for crash reports from the Play Console.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Robolectric/test-only and optional annotations referenced by dependencies.
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
