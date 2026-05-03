# Keep stack traces useful for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AWS SDK uses reflection extensively for request/response models
-keep class com.amazonaws.** { *; }
-keep interface com.amazonaws.** { *; }
-dontwarn com.amazonaws.**

# Conscrypt / OkHttp transitive references that don't exist at runtime
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# AIDL contract — public API surface for clients
-keep class com.artmedical.cloud.api.** { *; }
-keep interface com.artmedical.cloud.api.** { *; }

# Room entities, DAOs, and database (accessed via reflection by Room)
-keep class com.artmedical.dcc.service.data.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * { @androidx.room.* <methods>; }

# Apache MQTT client
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Parcelable @Parcelize requires the CREATOR field
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
