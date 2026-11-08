### Added

- **Verbundene Anwendungen: Raffinerieaufträge stornieren und löschen.** Eine Anwendung mit
  `exchange.refinery.write` storniert einen eigenen Auftrag wie die Web-Oberfläche oder löscht einen
  nicht eingelagerten Auftrag samt Gütern; gebuchter Bestand bleibt. Zu viele Löschungen auf einmal
  muss das Mitglied auf „Verbundene Anwendungen" bestätigen, und ein Rückgängig-Machen stellt einen
  gelöschten Auftrag unter seiner alten ID wieder her (REQ-XCH-055, REQ-XCH-056).
- **Raffinerie:** Löscht eine Anwendung einen Auftrag, dessen Detailseite offen ist, sperrt die
  Seite ihre Formulare und zeigt einen Hinweis mit Link zur Auftragsliste.
- **Audit:** neues Ereignis `REFINERY_ORDER_RESTORED` für einen so wiederhergestellten Auftrag.
