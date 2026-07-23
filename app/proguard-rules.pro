# Keep the USB-activated helper entry point in optimized release builds.
-keep class com.ajcoder.quietshield.dormant.engine.DormantShellMain {
    public static void main(java.lang.String[]);
}
