# Architecture

QuietShield Dormant separates the visible app from privileged actions.

## App layer

The Kotlin/Compose app loads installed applications, classifies them into User, System, and Core groups, stores per-app rules, displays runtime states, records beta results, and guides Wireless Debugging setup.

## Wireless setup layer

The app generates a private local pairing identity and uses Android's Wireless Debugging pairing flow. After pairing, it connects to the phone's wireless ADB service, starts the small Dormant shell helper, verifies the response, and disconnects the setup session.

## Privileged helper

The helper listens only on the phone's loopback address and requires a randomly generated private setup token. It performs validated standby, close, enable, disable, and runtime-inspection requests. Static core-package rules are enforced again inside the helper.

## Monitoring

The foreground monitoring service watches only saved rules and uses adaptive checks rather than continuous one-second scanning. It pauses actions for foreground apps, hard-protected packages, media playback, and allowed useful background work.

## Results

Local beta metrics record capped action history, restart signals, background-service samples, and optional screen-off battery samples. No cloud service or AI model is used.

## Recovery

A small recovery service attempts to restore a saved pairing after restart or app update. Android may still require the user to turn Wireless Debugging on and tap Restore. Dormant never reports automatic closing as active unless the helper responds.
## Automatic wireless pairing discovery

A short-lived foreground service uses Android network service discovery to watch for the temporary `_adb-tls-pairing._tcp.` service while the system pairing-code screen is open. The changing address and port stay internal. A direct-reply notification accepts only the six-digit code, pairs the private Dormant identity, discovers the normal secure connection, starts the helper, and then stops the pairing service.

