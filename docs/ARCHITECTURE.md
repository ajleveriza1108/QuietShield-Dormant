# Architecture

QuietShield Dormant separates its visible app, safety policy, monitoring, and privileged command transport.

## App layer

The Kotlin/Compose app loads installed applications, classifies them into User, System, and Core groups, stores per-app rules, displays runtime states, records beta results, and guides Wireless Debugging setup.

## Wireless setup layer

The app creates a private local pairing identity and uses Android's Wireless Debugging pairing flow. A short-lived foreground service discovers `_adb-tls-pairing._tcp.` while Android's pairing-code screen is open. The address and changing port remain internal; the user enters only the six-digit code through the notification.

After pairing, Dormant discovers the normal secure ADB connection and verifies it with a harmless shell command. Pairing success and command-connection success are reported separately.

## Direct wireless engine

Wireless mode does not launch a detached `app_process` server. The Dormant foreground monitor retains the authenticated ADB manager and opens a short shell stream only when it needs to inspect runtime state or apply a saved action. A failed stream triggers one controlled reconnect. This removes the helper-start timeout seen after successful Samsung pairing.

Every package-changing command is validated in the app before it is sent. Dormant rejects malformed package names, its own package, and known Core Apps.

## USB compatibility fallback

The optional computer activation script can still start the older token-protected localhost helper. `DormantEngineClient` uses the direct wireless engine first and falls back to the local helper only when it is actually available.

## Monitoring

The foreground monitoring service watches only saved rules and uses adaptive checks rather than continuous one-second scanning. It pauses actions for foreground apps, hard-protected packages, media playback, and allowed useful background work.

## Results

Local beta metrics record capped action history, restart signals, background-service samples, and optional screen-off battery samples. No cloud service or AI model is used.

## Recovery

A recovery service attempts to reconnect with the saved pairing after restart or app update. Android may still require the user to turn Wireless Debugging on and tap Restore. Dormant never reports automatic closing as active unless a direct shell verification or the optional USB helper responds.
