# Security and safety

QuietShield Dormant Beta 1 is experimental software with privileged control available only after the user deliberately completes Wireless Debugging setup.

## Safety boundaries

- Core Apps are visible but permanently locked.
- Current launcher, keyboard, phone, messages, active accessibility services, device-management services, and Dormant itself are protected.
- System Apps require caution and are never automatically classified as aggressive.
- Automatic Close sooner applies only to eligible User Apps.
- Media-protected apps are not managed while playback is detected.
- A missing or unresponsive helper immediately turns automatic closing off.
- Disable/enable actions require explicit confirmation.
- The app never hides Developer Options or bypasses another app's security check.

## Wireless identity

The Wireless Debugging RSA key and certificate are generated locally and stored in private app storage. Use Results > Forget wireless setup to remove them.

## Reporting

Do not post pairing codes, private keys, account information, banking information, or full personal notification content in issue reports. Beta reports contain package names and Dormant activity, so review them before sharing publicly.

Report security concerns privately to the repository owner rather than opening a public issue containing sensitive details.
