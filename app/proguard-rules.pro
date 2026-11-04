# Gson models (fields are matched by @SerializedName at runtime)
-keep class com.nicolous.youtubedownloader.data.model.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep interface com.nicolous.youtubedownloader.data.api.YouTubeApiService { *; }
-dontwarn retrofit2.KotlinExtensions*

# OkHttp / Okio optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Strip log calls in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
