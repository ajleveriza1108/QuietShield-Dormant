# Architecture

## App process

The normal Android app provides:

- installed-app inventory and classification
- User Apps, System Apps, and Core Apps screens
- saved behavior in Preferences DataStore
- app-activity monitoring after user approval
- music and active-alert protection after user approval
- a foreground monitoring service only while automatic closing is on
- a Quick Setting for on/pause control

## Test helper

Alpha 3 includes a separate shell-level helper started from the Windows USB activation script. The app talks to it only through a loopback socket protected by a random private token.

Supported test commands:

- health check
- list running package processes
- place an eligible app in standby
- mark an eligible app active
- force-stop an eligible app
- stop the helper

The helper rejects malformed package names, QuietShield Dormant itself, and known Core packages. The app also loads dynamic Core Apps before monitoring starts, covering the current launcher, keyboard, Phone app, and Messages app.

## Enforcement rules

- Only explicitly saved policies are evaluated.
- Protected apps are skipped.
- Core Apps are skipped.
- Apps in the foreground are skipped.
- Always-let-it-work apps are skipped.
- Smart handling pauses timers for active alerts or active playback.
- Media Protection pauses timers while media is playing.
- Automatic closing stops immediately if the helper health check fails.

## Resource design

- No AI model
- No cloud processing
- Event-based usage tracking
- Longer monitoring interval while the screen is off
- UI process is not kept as a full screen in memory
- Limited DataStore records
