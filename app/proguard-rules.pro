# Keep the USB-activated helper entry point in optimized release builds.
-keep class com.ajcoder.quietshield.dormant.engine.DormantShellMain {
    public static void main(java.lang.String[]);
}

# Wireless Debugging pairing and the shell helper entry point.
-keep class com.ajcoder.quietshield.dormant.engine.DormantShellMain { public static void main(java.lang.String[]); }
-keep class com.ajcoder.quietshield.dormant.wireless.DormantAdbConnectionManager { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn android.sun.**
-dontwarn org.conscrypt.**
