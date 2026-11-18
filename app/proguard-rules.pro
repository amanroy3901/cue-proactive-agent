# R8 rules for the "demo" build. Keep what is reached by name from native code or reflection.

# LiteRT and the LFM3 engine: JNI calls back into these classes by name.
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.cue.brain.ondevice.** { *; }

# A2UI (alpha): component catalogs and protocol models are read reflectively.
-keep class androidx.a2ui.** { *; }
-keep class androidx.compose.material3.a2ui.** { *; }

# Room entities and DAOs are generated code; keep the domain models whole for JSON and Room.
-keep class com.cue.domain.** { *; }

# Readable stack traces from testers.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
