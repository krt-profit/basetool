### Fixed

- **Exchange: regelmäßiger Sync füllt das Speicherbudget nicht mehr.** Ein Mitglied, dessen
  Anwendung alle 5 Minuten Lager und Schiffe abgleicht, bekam Schreibvorgänge mit
  `503 EXCHANGE_BUDGET_EXHAUSTED` abgewiesen. Das Gateway hält Idempotency-Antworten jetzt 1 Stunde
  statt 24 und hat größere Budgets (4 MiB pro Mitglied, 64 MiB pro Anwendung, 128 MiB gesamt);
  `Retry-After` einer Budget-Abweisung nennt die echte Wartezeit statt höchstens einer Stunde
  (REQ-XCH-020, REQ-XCH-023, ADR-0221).
