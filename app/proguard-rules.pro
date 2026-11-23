# ==============================================================================
# ShareFlow Production R8 / ProGuard Configuration
# Aggressive release shrinking, optimization, and APK size reduction while
# strictly preserving Room, Firebase, Coroutines, Media3, CameraX, and ML Kit.
# ==============================================================================

# Aggressive R8 / ProGuard optimization passes & DEX size compaction
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-repackageclasses 'sf'

# Preserve crash-diagnostic line numbers & metadata required by reflection/Room/Firebase
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------------------
# 1. Android Manifest-Referenced Entry Points & Core Components
# ------------------------------------------------------------------------------
-keep public class com.example.ShareFlowApplication { *; }
-keep public class com.example.MainActivity { *; }
-keep public class com.example.shareflow.media.music.ShareFlowMediaSessionService { *; }
-keep public class com.example.shareflow.data.apps.PackageInstallStatusReceiver { *; }
-keep class androidx.core.splashscreen.** { *; }

# Keep @Keep annotated symbols explicitly
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# ------------------------------------------------------------------------------
# 2. Room Database Integrity (Database, Generated *_Impl, DAOs, Entities, Converters)
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class * extends androidx.room.RoomDatabase$Callback
-keep class com.example.shareflow.data.local.database.**_Impl { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}
-keepclassmembers class * {
    @androidx.room.TypeConverter <methods>;
    @androidx.room.Embedded <fields>;
    @androidx.room.ColumnInfo <fields>;
    @androidx.room.PrimaryKey <fields>;
}
-keep class androidx.room.RoomMasterTable { *; }
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------------------------
# 3. Firebase & Google Play Services Integrity (Firestore, Auth, AI Logic, App Check)
# Preserves component registrars, init providers, and Firestore model annotations
# without preventing R8 from tree-shaking unused Firebase classes.
# ------------------------------------------------------------------------------
-keep class com.google.firebase.provider.FirebaseInitProvider { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
    java.util.List getComponents();
}
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
    @com.google.firebase.firestore.DocumentId <fields>;
    @com.google.firebase.firestore.ServerTimestamp <fields>;
    @com.google.firebase.firestore.Exclude <fields>;
    @com.google.firebase.firestore.Exclude <methods>;
}
-keep @com.google.firebase.firestore.IgnoreExtraProperties class * {
    <fields>;
    <init>(...);
}
-keepclassmembers class * extends com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable {
    public static final *** CREATOR;
}
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ------------------------------------------------------------------------------
# 4. Kotlin Coroutines, DataStore, and Reflection Metadata
# ------------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ------------------------------------------------------------------------------
# 5. Precision Keep Rules for Media3, CameraX, Biometric, OkHttp & ML Kit
# Avoid blanket `** { *; }` rules so R8 removes unused classes and methods!
# ------------------------------------------------------------------------------
-keep class androidx.media3.session.MediaSessionService { *; }
-keep class * extends androidx.media3.session.MediaSessionService { *; }
-keep class androidx.camera.camera2.Camera2Config$DefaultProvider { *; }
-keep class androidx.biometric.BiometricPrompt { *; }
-keep class androidx.biometric.BiometricPrompt$* { *; }
-keep class com.google.mlkit.vision.barcode.BarcodeScanning { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.mlkit.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ------------------------------------------------------------------------------
# 6. Release Performance & Security: Strip Verbose / Debug / Info Logs
# Preserves Log.w and Log.e for genuine runtime error diagnostics.
# ------------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
