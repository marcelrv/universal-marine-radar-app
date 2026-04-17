# ProGuard / R8 rules for mayara-app

# ---- JNI: keep all native method declarations ----
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the RadarJni object and its external fun declarations
-keep class com.marineyachtradar.mayara.jni.RadarJni {
    *;
}

# ---- Domain / data model data classes ----
# Needed because Gson/serde or reflection may access these
-keep class com.marineyachtradar.mayara.data.model.** { *; }

# ---- Wire generated protobuf classes ----
-keep class com.squareup.wire.** { *; }
-keepclassmembers class * extends com.squareup.wire.Message { *; }

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---- Kotlin ----
-keepattributes *Annotation*, Signature, Exception
-keep class kotlin.Metadata { *; }

# ---- DataStore / Preferences ----
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---- Kotlin Coroutines ----
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ---- AndroidX Lifecycle / ViewModel ----
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ---- Compose Navigation ----
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**
