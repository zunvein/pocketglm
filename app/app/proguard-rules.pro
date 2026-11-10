# R8 rules for PocketGLM (applied to debug too, to shrink the APK).

# WebView calls these by name from JavaScript (window.PocketGLM.*); the
# shrinker cannot see that, so keep every annotated method.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# App code is tiny; keeping it untouched removes any reflection surprises
# and costs almost nothing. The savings come from AppCompat/Kotlin/coroutines.
-keep class dev.zunvein.pocketglm.** { *; }
