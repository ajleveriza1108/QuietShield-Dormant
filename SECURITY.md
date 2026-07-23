# Security Policy

QuietShield Dormant is intended to control other applications only after explicit user configuration and only through documented, visible operating modes.

Please report security-sensitive issues privately to the repository owner rather than publishing device-specific bypass details in a public issue.

## Non-negotiable protections

- Core Apps are blocked at both UI and future engine layers.
- No banking-app integrity bypasses.
- No hidden Accessibility automation.
- No silent app disabling.
- No remote command execution.
- No cloud upload of installed-app inventory or notification contents.

## Sideload-safe permission policy

The Alpha 3 R5 test build does not declare Notification Listener or Accessibility services. This reduces sideload-install blocking and avoids reading notification content.
