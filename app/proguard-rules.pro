# =============================================================================
# R8 / ProGuard rules for AfamFresh
#
# R8 is enabled for release (isMinifyEnabled = true). It renames and strips
# anything it cannot prove is used, which breaks two things in this app unless
# they are kept explicitly:
#
#   1. Gson models. Gson reads field NAMES via reflection. R8 renames fields,
#      so `itemname` becomes `a` and JSON parsing silently produces objects
#      with every field null — no crash, no error, just empty screens.
#
#   2. Retrofit interfaces. Method signatures and annotations are read
#      reflectively at runtime to build the HTTP calls.
#
# These failures do NOT show up in a debug build, which is exactly what makes
# them dangerous: everything works until the release build ships.
# =============================================================================

# -----------------------------------------------------------------------------
# App models — the critical one.
#
# Keeping the class alone is not enough: the FIELDS must survive with their
# original names, because @SerializedName only covers the wire name, and any
# field without that annotation relies entirely on its Kotlin name matching.
# -----------------------------------------------------------------------------
-keep class com.techaus.afamfresh.models.** { *; }
-keepclassmembers class com.techaus.afamfresh.models.** { <fields>; }

# Delivery pricing is parsed from config.php into these classes.
-keep class com.techaus.afamfresh.config.** { *; }

# -----------------------------------------------------------------------------
# Gson
# -----------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-dontwarn sun.misc.**

# TypeToken relies on generic signatures surviving; it is used in
# LocalAddressRepository to deserialise List<Address>.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

-keep class com.google.gson.** { *; }

# Anything annotated for Gson keeps its field names.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Gson's reflective adapters need no-arg constructors to exist.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <init>(...);
}

# -----------------------------------------------------------------------------
# Retrofit
# -----------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Retrofit builds implementations of these interfaces reflectively.
-keep,allowobfuscation interface retrofit2.Call
-keep,allowobfuscation class retrofit2.Response

-keep interface com.techaus.afamfresh.api.** { *; }

# Retrofit does reflection on generic parameters; R8 full mode strips those.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

-dontwarn retrofit2.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# R8 warns about these on the Retrofit/OkHttp classpath; they are compile-only.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# -----------------------------------------------------------------------------
# OkHttp
# -----------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# -----------------------------------------------------------------------------
# Kotlin / coroutines
# -----------------------------------------------------------------------------
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# kotlinx-serialization is on the classpath.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# -----------------------------------------------------------------------------
# Firebase Cloud Messaging
#
# The messaging service is instantiated by the framework from the manifest,
# so nothing in our code references it and R8 would otherwise remove it.
# -----------------------------------------------------------------------------
-keep class com.techaus.afamfresh.services.** { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# -----------------------------------------------------------------------------
# Google Play Services (Maps, Location, Auth)
# -----------------------------------------------------------------------------
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# -----------------------------------------------------------------------------
# osmdroid
# -----------------------------------------------------------------------------
-dontwarn org.osmdroid.**
-keep class org.osmdroid.** { *; }

# -----------------------------------------------------------------------------
# Compose
# -----------------------------------------------------------------------------
-dontwarn androidx.compose.**

# -----------------------------------------------------------------------------
# Keep line numbers so release crash reports remain readable, but hide the
# original source file name.
# -----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
