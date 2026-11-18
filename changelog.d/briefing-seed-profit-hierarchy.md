### Fixed

- **Testsystem: Einweisungsdaten.** `scripts/seed-briefing.py` läuft wieder durch: Ein einziger
  Profit-Bereich trägt alle Test-Staffeln und das Spezialkommando, die Aufträge legt der Logistiker
  an, und der Seed-Client bekommt den `kc-admin-api`-Scope, den Keycloak 26.8.2 für das Admin-API
  verlangt. Nach dem Leeren der Datenbank braucht der Seed vorher den P4K-Import.
