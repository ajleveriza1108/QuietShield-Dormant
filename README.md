# QuietShield Dormant

QuietShield Dormant is an Android background-app manager with per-app sleep and closing choices, conservative Core App protection, group controls, playing-audio protection, and an optional privileged helper.

## Current milestone: v0.1.0-alpha4 Wireless

### Wireless activation inside the phone

Alpha 4 adds real on-device Wireless Debugging pairing for Android 11 and newer.

1. Open QuietShield Dormant and tap the switch at the top right.
2. Allow app activity access.
3. Open Developer options and turn on Wireless Debugging.
4. Tap **Pair device with pairing code**.
5. Return to Dormant and enter the address and pairing port, plus the six-digit code.
6. Tap **Pair and turn on**.

The app pairs with the user's own phone, connects to Android's Wireless Debugging service, starts the Dormant helper, verifies that it responds, and then closes the ADB connection. No Windows computer or USB cable is required for the normal setup.

The private pairing identity stays in the app's private storage. After a phone restart, turn Wireless Debugging on and tap **Restore automatic closing**. A new six-digit code is normally needed only if Android forgot the pairing, debugging authorizations were revoked, or app data was cleared.

`04_USB_BACKUP_ACTIVATION.bat` remains available only as a development fallback.

### App list and controls

- Three full-height tabs: **User Apps**, **System Apps**, and **Core Apps**
- Real installed app icons
- Search with a visible **×** clear button
- Per-app **Running now** badge and running-only filter while the helper is active
- **Select all** and group behavior changes
- **Reset this tab**
- **App info** for every listed app
- Core Apps remain visible but read-only

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

The **Dormant** Quick Setting turns automatic closing on or pauses it. It shows only **On**, **Paused**, or **Setup needed**.

## Android identity

- Application ID: `com.ajcoder.quietshield.dormant`
- Debug Application ID: `com.ajcoder.quietshield.dormant.debug`
- Minimum Android: Android 10 / API 29
- Wireless pairing: Android 11 or newer
- Compile SDK: API 36
- Target SDK: API 36
- Version: `0.1.0-alpha4-wireless`

## Build on Windows

Run the complete installer package or use:

```text
01_BUILD_DEBUG.bat
```

The debug APK is copied to:

```text
release\debug\QuietShield-Dormant-v0.1.0-alpha4-wireless-debug.apk
```

## Safety model

- **User Apps:** configurable.
- **System Apps:** configurable with caution.
- **Core Apps:** visible but permanently locked.
- No app is managed until the user saves a non-protected behavior.
- Music and active audio are protected conservatively.
- No automatic system-app disabling is included.
- The app does not hide Developer Options or bypass another app's security checks.
- Automatic closing is shown as active only after the helper responds.

## Important Alpha limitation

Wireless Debugging and the embedded ADB library are experimental. The third-party ADB library has not undergone a published security audit. Pair only with a phone you own, keep the pairing-code screen private, and turn Wireless Debugging off when it is not needed.
