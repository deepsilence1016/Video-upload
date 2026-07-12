# ============================================================
# ProGuard Rules — Vision Agent Production Release
# ============================================================

# Keep application class
-keep class com.visionagent.VisionAgentApp { *; }

# Keep all Hilt-related classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.DefineComponent class * { *; }

# Keep Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.TypeConverter class * { *; }

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep Coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.** { *; }

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Accessibility Service
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Keep all event classes (sent via EventBus)
-keep class com.visionagent.core.event.** { *; }

# Keep native bridge classes
-keep class com.visionagent.core.vision.VisionNativeBridge { *; }
-keep class com.visionagent.core.ocr.OCRNativeBridge { *; }
-keep class com.visionagent.core.screen.ROIChangeDetector { *; }

# Keep data models for serialization
-keep class com.visionagent.data.** { *; }
-keep class com.visionagent.domain.model.** { *; }

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Prevent stripping of methods used by native code
-keepclassmembers class com.visionagent.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Optimization settings
-optimizationpasses 5
-mergeinterfacesaggressively
-allowaccessmodification
-repackageclasses 'com.visionagent.obfuscated'
