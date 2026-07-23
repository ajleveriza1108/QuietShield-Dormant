# QuietShield Dormant

QuietShield Dormant is a local Android background-app manager with user-selected sleep and close rules, Wireless Debugging activation, media protection, safety classification, and measured beta results.

## Current milestone: v0.2.0 Beta 1 R2

This beta combines the planned Alpha 4, Alpha 5, Alpha 6, and Beta 1 milestones into one test build:

- On-device Wireless Debugging pairing and restoration
- USB activation retained only as a development fallback
- User Apps, System Apps, and Core Apps tabs
- Real app icons, search clearing, select-all, group behavior, and per-tab reset
- Runtime labels: Open now, Playing media, Working in background, Kept ready by Android, Sleeping, Closed, and Not running
- Sleep then close, Sleep only, Close only, and Leave this app alone
- Background and inactive timers from 1 minute through custom values
- Media protection and Smart background activity protection
- Hard protection for core components and active accessibility/device-management services
- Close-sooner suggestions with Off, Suggest only, and Apply automatically modes
- Optional manual enable/disable for eligible apps
- Three-day before-management and managed battery comparison
- Action history, beta report sharing, and compatibility checks
- Best-effort restoration after restart when Wireless Debugging is available
- Dark AMOLED, Dark OLED, Dirty White, and Follow System themes
- Optimized beta APK with R8 and resource shrinking

## Important beta limitation

Automatic closing still requires Android's Wireless Debugging authority. The helper stops when the phone fully restarts and Dormant must restore it. The beta attempts restoration when Android allows it, but the user may still need to turn Wireless Debugging on and tap Restore.

Do not use the first automatic-closing tests on banking apps, authenticators, alarms, the launcher, keyboard, phone, messages, VPN, accessibility services, or health/safety apps.

## Windows build

Run:

```text
01_BUILD_BETA.bat
```

The optimized beta APK is copied to:

```text
release\beta\QuietShield-Dormant-v0.2.0-beta1-r2.apk
```

Install it through:

```text
02_INSTALL_BETA_TO_PHONE.bat
```

## Wireless setup

1. Open QuietShield Dormant and tap the top-right switch.
2. Allow app activity access.
3. Turn on Wireless Debugging.
4. Choose Pair device with pairing code.
5. Keep the pairing-code screen open.
6. Enter only the six-digit code in the Dormant notification and tap Pair.

## Beta testing

Follow `docs/BETA_TEST_PLAN.md`. Record phone-brand results in `docs/COMPATIBILITY_MATRIX.md` and use the in-app Results screen to share a local beta report.

## Privacy

- No advertising
- No trackers
- No cloud behavior analysis
- Pairing keys stay in the app's private storage
- Local activity history is capped and can be cleared with app data
- No Notification Listener permission

See `SECURITY.md`, `THIRD_PARTY_NOTICES.md`, and `docs/ARCHITECTURE.md`.
