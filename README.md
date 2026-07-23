# QuietShield Dormant

QuietShield Dormant is an original Android background-app manager with clear per-app choices, conservative Core App protection, music safeguards, group controls, and an optional automatic-closing test engine.

## Current milestone: v0.1.0-alpha3 R5

### App list and controls

- Three full-height tabs: **User Apps**, **System Apps**, and **Core Apps**
- Search with a visible **×** clear button
- **Running now** filter while Automatic Closing Setup is available
- **Select all** and group behavior changes for User Apps and System Apps
- **Reset this tab** restores only the open tab to **Leave this app alone**
- Real installed app icons appear beside every listed app
- **App info** button for every installed app
- Core Apps remain visible but read-only
- Current launcher, keyboard, dialer, Messages app, and QuietShield Dormant are protected dynamically

### Saved behavior

- Sleep, then close
- Sleep only
- Close only
- Leave this app alone
- First and second timers: 1, 2, 5, 10, 15, 30, 60 minutes, or custom
- Always let it work
- Let it work when needed
- Do not let it work
- Keep playing apps active
- Close this app sooner

All apps start as **Leave this app alone** until the user explicitly chooses another behavior.

### Quick Setting

The **Dormant** Quick Setting turns automatic closing on or pauses it. It shows only **On**, **Paused**, or **Setup needed**. It does not show a notification counter.

### Automatic closing test

Alpha 3 includes a USB-activated test helper. It can apply the behavior saved for explicitly selected apps while the helper is responding.

Test order:

1. Build with `01_BUILD_DEBUG.bat`.
2. Install with `02_INSTALL_DEBUG_TO_PHONE.bat`.
3. In the app, allow app activity access.
4. Connect one unlocked phone by USB.
5. Run `04_ACTIVATE_AUTOMATIC_CLOSING.bat`.
6. Select a nonessential test app and save **Close only** with a short timer.
7. Leave the test app and wait for the timer.

To stop the test helper, run `05_STOP_AUTOMATIC_CLOSING.bat`.

This Alpha test does not hide or bypass banking-app security checks. Before opening a banking app, stop the test helper and turn off USB debugging and Developer Options.

## Install-safe testing change

Alpha 3 R5 keeps the install-safe permission set and repairs the main app list layout. Google Play Protect can automatically block file-manager or browser installations of apps that declare this sensitive access. Music protection now uses Android audio activity instead. While audio is playing, automatic management pauses conservatively for apps with media protection enabled. Important-alert inspection is not included in this test build.

## Android identity

- Application ID: `com.ajcoder.quietshield.dormant`
- Debug Application ID: `com.ajcoder.quietshield.dormant.debug`
- Minimum Android: Android 10 / API 29
- Compile SDK: API 36
- Target SDK: API 36
- Version: `0.1.0-alpha3-r5`

## Build on Windows

Requirements:

- Android Studio with Android SDK Platform 36
- Android Studio JBR or JDK 17
- Git

The debug APK is copied to:

```text
release\debug\QuietShield-Dormant-v0.1.0-alpha3-r5-debug.apk
```

## Safety model

- **User Apps:** configurable.
- **System Apps:** configurable with caution and never changed automatically by classification alone.
- **Core Apps:** visible but permanently locked.
- No app is managed until the user saves a non-protected behavior.
- Music and active media are protected conservatively without reading notification content.
- No automatic app disabling is included in this Alpha.

## Development boundaries

- No copied Brevent or Greenify source, branding, assets, or wording.
- No fake RAM-cleaning or battery-boost percentages.
- No automatic system-app disabling.
- No hiding or bypassing banking-app security checks.
- No claim that automatic closing is active unless the test helper responds.


### Alpha 3 R5 interface repair

- Keeps the title and controls below the phone status bar.
- Removes the disabled Running now chip when setup is unavailable.
- Shows a separate Running now badge inside every app row when that app is running.
- Keeps the saved behavior label visible even while the app is running.
- Uses a tighter group-control area and reduces unused list spacing.
