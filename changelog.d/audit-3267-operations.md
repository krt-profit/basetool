### Fixed

- Operational cleanup and restore checks preserve failure outcomes and their previous success timestamp. Drift checks also cover installed operational units and companion files.
- Structured logging masks credentials before JSON serialization, and Keycloak integration responses have explicit byte limits.
- The OTP setup QR image has a localized description for assistive technology.
- Reference-data caches use separate size-based admission budgets; large catalogue responses remain complete.
