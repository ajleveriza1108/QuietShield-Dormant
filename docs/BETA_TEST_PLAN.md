# QuietShield Dormant v0.2.0 Beta 1 R4 test plan

Use a secondary or carefully backed-up phone first. Begin with harmless shopping apps or games.

## 1. Installation and interface

- Build with `01_BUILD_BETA.bat`.
- Confirm tests, lint, optimized APK, and size report pass.
- Install with `02_INSTALL_BETA_TO_PHONE.bat`.
- Confirm the top bar stays below the status bar and the list stays above the navigation bar.
- Confirm app icons, search clear button, all three tabs, select all, Set behavior, and Reset this tab.

## 2. Wireless activation

- Grant app activity access.
- Turn on Wireless Debugging and open Pair device with pairing code.
- Keep the pairing-code screen open. Dormant should find the changing port automatically.
- Enter only the six-digit code in the Dormant notification and tap Pair.
- Confirm Automatic closing is on.
- Turn the screen off for five minutes, return, and confirm the helper is still ready.

## 3. Automatic closing

Use one harmless app:

- Set Close only after 1 minute.
- Open it, return Home, and wait.
- Confirm it changes to Closed and performs a cold start next time.

Then test:

- Sleep only
- Sleep, then close
- 1 minute grammar
- Custom timeout
- Pause and resume from Quick Settings

## 4. Safety

Confirm Dormant refuses or skips:

- Current launcher
- Current keyboard
- Phone and Messages
- Dormant itself
- Core Apps
- Enabled accessibility services
- Device-management apps

Do not test banking, authenticator, health, emergency, alarm, VPN, or password-manager apps aggressively.

## 5. Media and useful work

- Play music, leave the player, and wait beyond its timer.
- Confirm Playing media remains visible and the player is not closed.
- Stop playback and confirm the timer restarts.
- Test Smart background activity on a harmless syncing app.

## 6. Close-sooner suggestions

- Keep Suggest only as the first test.
- Observe an app that repeatedly restarts or stays active.
- Confirm the reason is shown and can be accepted, dismissed for one day, or hidden permanently.
- Test Apply automatically only after safety tests pass; it must affect User Apps only.

## 7. Restart recovery

- Save rules and restart the phone.
- Confirm rules remain saved.
- Turn Wireless Debugging on if needed.
- Confirm automatic restore or tap Restore automatic closing.
- Confirm Dormant never shows On while the helper is unavailable.

## 8. Battery comparison

- Pause automatic closing.
- Start the three-day before-management test from Results.
- After it completes, run automatic closing for another three days.
- Compare screen-off percentage per hour.
- Do not claim improvement unless enough samples exist.

## 9. Report

Open Results and share the beta report after reviewing package names. Record the device result in `COMPATIBILITY_MATRIX.md`.
