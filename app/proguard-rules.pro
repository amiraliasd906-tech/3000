# This file was missing from the project even though app/build.gradle.kts's
# release buildType references it (proguardFiles(..., "proguard-rules.pro")),
# which would fail any release/minified build ("proguard-rules.pro (No such
# file or directory)"). These are safe, standard rules for the libraries this
# app actually uses with minifyEnabled/isShrinkResources on.

# --- kotlinx.serialization ---
# Keep the generated serializer() companions/classes for every @Serializable
# model used for the Anthropic API request/response bodies (OwnerDetector,
# ListingAiAssistant) and the WebView JS-bridge payloads (ExtractedListing).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.divarsmartsearch.app.**$$serializer { *; }
-keepclassmembers class com.divarsmartsearch.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.divarsmartsearch.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room ---
# Entities/DAOs are referenced through generated code (KSP) and reflection
# for schema validation; keep the entity/database classes themselves.
-keep class com.divarsmartsearch.app.data.local.entity.** { *; }
-keep class com.divarsmartsearch.app.data.local.AppDatabase { *; }

# --- WebView JavaScript bridge ---
# Real bug found: nothing protected the @JavascriptInterface-annotated
# methods (HeadlessDivarScanner's and DivarWebViewScreen's "AndroidBridge")
# from R8. Without this rule, a release/minified build renames or strips
# those methods — the injected JS calls AndroidBridge.onListingsExtracted(...)
# by that exact name, so the call silently fails and NO listing ever makes
# it from the page into the app. This is invisible in a debug build (where
# isMinifyEnabled = false), which is exactly why it's easy to miss: the app
# can look completely fine during development and then extract nothing at
# all the moment a real release APK is built.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- OkHttp / Okio (used by ListingDetailFetcher, OwnerDetector's direct
# Anthropic API call, and ListingAiAssistant) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
