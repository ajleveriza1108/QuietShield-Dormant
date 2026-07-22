# Architecture

## Layers

### Domain

Pure models and safety rules:

- `InstalledApp`
- `AppPolicy`
- `CorePackageRules`
- sleep, sync, section, and theme enums

### Data

- `AppCatalogRepository`: loads packages through PackageManager.
- `PolicyRepository`: persists themes and per-package policy records through Preferences DataStore.

### UI

- `QuietShieldViewModel`: coordinates app inventory, policy data, search, tabs, and permission status.
- `QuietShieldDormantApp`: Compose UI, policy editor, theme controls, and three-tab inventory.

## Future privileged boundary

Privileged commands must not be implemented directly inside Compose screens or the catalog repository. A future `EngineClient` interface will expose a small allowlisted command set:

- `setStandby(packageName)`
- `forceStop(packageName)`
- `setEnabled(packageName, enabled)`
- `queryState(packageName)`
- `healthCheck()`

Every command must pass through a safety gate that independently rejects Core Apps. The privileged process must repeat the same rejection rather than trusting UI validation alone.

## Classification order

1. Dynamic device roles: own app, launcher, keyboard, dialer, SMS.
2. Known Android core rules.
3. Android system/update flags.
4. Ordinary user application.

This order prevents a downloaded launcher or keyboard from being treated as an ordinary manageable User App.
