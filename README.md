# QuietShield Dormant

## Alpha 2 interface update

This test build improves the app list and behavior editor:

- User Apps, System Apps, and Core Apps use the full available screen.
- User and System Apps support Select all and group behavior changes.
- The behavior editor scrolls above the phone navigation bar.
- Switches now stay beside their visible labels.
- All timeout text uses correct singular and plural wording.
- User-facing wording avoids developer terms.
- Automatic app closing is still not active in this test build.

QuietShield Dormant is a safety-first Android background-app manager. The project is inspired by the useful ideas behind standby managers, but it is being built as an original product with transparent capability reporting, conservative core-app protection, media safeguards, and per-app policies.

## Current milestone: v0.1.0-alpha2 Foundation

This first milestone provides the real Android application foundation:

- Three app tabs: **User Apps**, **System Apps**, and **Core Apps**
- Installed-package inventory and searchable package names
- Dynamic protection of the current launcher, keyboard, dialer, SMS app, and QuietShield Dormant itself
- Conservative Android core-package rules
- Read-only Core Apps with permanent protection explanations
- Per-app policy editor
- Sleep modes:
  - Standby → Force Stop
  - Standby Only
  - Force Stop Only
  - Protected
- Background timeout: 1, 2, 5, 10, 15, 30, 60 minutes, or custom
- Inactive timeout: 1, 2, 5, 10, 15, 30, 60 minutes, or custom
- Allow Sync, Smart Sync, and No Background Sync policy storage
- Media Protection and Manual Aggressive switches
- Persisted policies using Preferences DataStore
- Dark AMOLED, Dark OLED, Dirty White, and Follow System themes
- Usage Access status and direct Settings shortcut
- Unit tests for known core-package rules
- GitHub Actions validation

## Foundation repair status

- R4 removes an obsolete explicit `androidx.compose.foundation.layout.weight` import.
- `Modifier.weight(...)` remains used only inside `RowScope`, where Compose exposes it as a scoped modifier.
- The PowerShell 5.1 preflight, SDK 36 dependency locks, build validation, rollback, and Git safety remain enabled.

## Honest capability status

v0.1.0-alpha2 does **not** yet execute automatic standby, force-stop, disable, or enable commands. Those actions require a separately designed and verified privileged engine. The UI states this clearly so the foundation build never makes a false battery-saving claim.

## Android identity

- Application ID: `com.ajcoder.quietshield.dormant`
- Minimum Android: Android 10 / API 29
- Compile SDK: API 36
- Target SDK: API 36
- Version: `0.1.0-alpha1`

## Build on Windows

Requirements:

- Android Studio with Android SDK Platform 36 and Build Tools 36.0.0
- JDK 17 or Android Studio's compatible bundled JDK
- Git

Run:

```bat
01_BUILD_DEBUG.bat
```

The debug APK will be copied to:

```text
release\debug\QuietShield-Dormant-v0.1.0-alpha2-debug.apk
```

## Safety model

- **User Apps:** configurable.
- **System Apps:** configurable only with caution; later dangerous actions must require confirmation.
- **Core Apps:** visible but permanently locked.

A package is not declared safe merely because it is absent from a static list. Dynamic roles such as the active launcher and keyboard are treated as Core Apps even when installed by the user.

## Development boundaries

- No copied Brevent or Greenify source, branding, assets, or wording.
- No fake RAM-cleaning or battery-boost percentages.
- No automatic system-app disabling.
- No hiding or bypassing banking-app security checks.
- No claim of automatic enforcement while the privileged engine is offline.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
