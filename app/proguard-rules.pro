# Keep JavaScript interface methods
-keepclassmembers class com.lmarena.app.ChatActivity$ArenaJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
