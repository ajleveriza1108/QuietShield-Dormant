# QuietShield Dormant Alpha 4 architecture

## Normal app process

The Compose UI, policy repository, app catalog, and monitor service run as the ordinary QuietShield Dormant application.

## Wireless activation

`WirelessActivationManager` uses `libadb-android` to:

1. Pair with the phone's Wireless Debugging service using the user-entered address and pairing port, plus the six-digit code.
2. Discover and connect to the normal Wireless Debugging service.
3. Create a private random helper token in the app's private files.
4. Start `DormantShellMain` through `app_process` with the installed APK as its class path.
5. Disconnect the temporary ADB session after the local helper responds.

The ADB private key and certificate are stored under the app's private `files/wireless_adb` directory and are not included in Android backup.

## Local helper

`DormantShellMain` runs as Android's shell user and listens only on `127.0.0.1:47531`. Every client must provide the private token before a command. Core package rules are checked again inside the helper.

## Reboot behavior

The helper process ends at reboot. Saved policies and the wireless pairing identity remain. The user turns Wireless Debugging on and taps **Restore automatic closing** to reconnect and restart the helper.
