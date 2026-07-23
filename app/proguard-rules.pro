# The helper is launched through app_process, so R8 must preserve its public entry point.
-keep class com.ajcoder.quietshield.dormant.engine.DormantShellMain {
    public static void main(java.lang.String[]);
}

# Wireless Debugging uses reflection and Android-compatible security classes.
-keep class com.ajcoder.quietshield.dormant.wireless.DormantAdbConnectionManager { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn android.sun.**
-dontwarn org.conscrypt.**
