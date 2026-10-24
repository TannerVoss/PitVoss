# Preserve the annotated API used by the bundled trusted app page.
-keepclassmembers class com.pitvoss.app.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
