# ShareFlow Production R8 / ProGuard Rules

# Preserve stack trace line numbers while redacting original source file names
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Manifest-referenced Android entry points
-keep public class com.example.ShareFlowApplication { *; }
-keep public class com.example.MainActivity { *; }
-keep public class com.example.shareflow.media.music.ShareFlowMediaSessionService { *; }

# Keep Room Database implementation and generated DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Kotlin Coroutines & Metadata needed for reflection/serialization
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Media3 / ExoPlayer classes referenced via reflection
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep AndroidX Biometric and CameraX components
-keep class androidx.biometric.** { *; }
-keep class androidx.camera.** { *; }

# Defense-in-depth: Strip verbose/debug Android log calls in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
