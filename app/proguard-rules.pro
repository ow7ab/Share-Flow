# ==============================================================================
# ShareFlow Production R8 / ProGuard Configuration
# Aggressive release shrinking, optimization, and APK size reduction while
# strictly preserving Firebase, Room, DataStore, Coroutines, Lifecycle,
# CameraX, Media3, Biometric, Coil, OkHttp, and Manifest entry points.
# ==============================================================================

# Aggressive R8 / ProGuard optimization passes & DEX size compaction
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-repackageclasses 'sf'

# Preserve crash-diagnostic line numbers & metadata required by reflection/Room/Firebase
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------------------
# 1. Android Manifest-Referenced Entry Points & Core Components
# ------------------------------------------------------------------------------
-keep public class com.example.ShareFlowApplication { *; }
-keep public class com.example.MainActivity { *; }
-keep public class com.example.shareflow.ui.viewer.ApkInstallIntentActivity { *; }
-keep public class com.example.shareflow.data.update.fcm.ShareFlowFirebaseMessagingService { *; }
-keep public class com.example.shareflow.media.music.ShareFlowMediaSessionService { *; }
-keep public class com.example.shareflow.network.nfc.ShareFlowNfcHceService { *; }
-keep public class com.example.shareflow.data.apps.PackageInstallStatusReceiver { *; }
-keep public class androidx.core.content.FileProvider { *; }
-keep class androidx.core.splashscreen.** { *; }

# Keep @Keep annotated symbols, Parcelable CREATORs, and Enum reflection methods
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ------------------------------------------------------------------------------
# 2. Room Database Integrity (Database, Generated *_Impl, DAOs, Entities, Converters)
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class * extends androidx.room.RoomDatabase$Callback { *; }
-keep class * extends androidx.room.migration.Migration { *; }
-keep class **.*_Impl extends androidx.room.RoomDatabase { *; }
-keep class com.example.shareflow.data.local.database.**_Impl { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Dao abstract class * { *; }
-keep @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}
-keepclassmembers class * {
    @androidx.room.TypeConverter <methods>;
    @androidx.room.Embedded <fields>;
    @androidx.room.Relation <fields>;
    @androidx.room.ColumnInfo <fields>;
    @androidx.room.PrimaryKey <fields>;
}
-keep class androidx.room.RoomMasterTable { *; }
-keep interface androidx.sqlite.db.SupportSQLiteOpenHelper$Factory { *; }
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------------------------
# 3. Firebase & Google Play Services Integrity (FCM, Installations, Core, Firestore)
# Preserves component registrars, init providers, messaging services, and model
# annotations without preventing R8 from tree-shaking unused Firebase classes.
# ------------------------------------------------------------------------------
-keep class com.google.firebase.provider.FirebaseInitProvider { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
    java.util.List getComponents();
}
-keep class com.google.firebase.messaging.FirebaseMessaging { *; }
-keep class com.google.firebase.messaging.FirebaseMessagingService { *; }
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { *; }
-keep class com.google.firebase.messaging.RemoteMessage { *; }
-keep class com.google.firebase.messaging.RemoteMessage$* { *; }
-keep class com.google.firebase.messaging.FirebaseMessagingRegistrar { *; }
-keep class com.google.firebase.installations.FirebaseInstallationsRegistrar { *; }
-keep class com.google.firebase.datatransport.TransportRegistrar { *; }
-keep class com.google.android.gms.common.annotation.KeepName
-keep @com.google.android.gms.common.annotation.KeepName class * { *; }
-keepclassmembers class * {
    @com.google.android.gms.common.annotation.KeepName <fields>;
    @com.google.android.gms.common.annotation.KeepName <methods>;
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
# 4. AndroidX Startup, Lifecycle, ViewModel, SavedState & DataStore Protobuf
# ------------------------------------------------------------------------------
-keep class * implements androidx.startup.Initializer {
    <init>();
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>();
    <init>(android.app.Application);
    <init>(androidx.lifecycle.SavedStateHandle);
}
-keep class * implements androidx.lifecycle.DefaultLifecycleObserver { *; }
-keep class * implements androidx.lifecycle.LifecycleEventObserver { *; }
-keep class * implements androidx.savedstate.SavedStateRegistry$AutoRecreated {
    <init>();
}
-keep class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# ------------------------------------------------------------------------------
# 5. Kotlin Coroutines & ServiceLoader Reflection Metadata
# ------------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory {
    <init>();
}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ------------------------------------------------------------------------------
# 6. Precision Keep Rules for Media3, CameraX, Biometric, Coil & OkHttp
# Avoid blanket `** { *; }` rules so R8 removes unused classes and methods.
# ------------------------------------------------------------------------------
-keep class androidx.media3.session.MediaSessionService { *; }
-keep class * extends androidx.media3.session.MediaSessionService { *; }
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.ui.PlayerView { *; }
-keep class androidx.camera.camera2.Camera2Config$DefaultProvider { *; }
-keep class * implements androidx.camera.core.CameraXConfig$Provider {
    <init>();
}
-keep class androidx.camera.view.PreviewView { *; }
-keep class androidx.biometric.BiometricPrompt { *; }
-keep class androidx.biometric.BiometricPrompt$* { *; }
-keep class coil.fetch.Fetcher$Factory { *; }
-keep class coil.decode.Decoder$Factory { *; }
-keep class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }
-dontwarn androidx.media3.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ------------------------------------------------------------------------------
# 7. Release Performance & Security: Strip Verbose / Debug / Info Logs
# Preserves Log.w and Log.e for genuine runtime error diagnostics.
# ------------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
