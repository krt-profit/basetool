### Fixed

- **Überwachung: neue Verbindungen wachsen mit dem Client.** `ExchangeClientDeviceLoginSurge` und `ExchangeInstallationSurge` vergleichen eine Stunde jetzt mit der stärksten Stunde des Clients in den sieben Tagen vor dem letzten Tag, nicht mehr mit dem Wochenschnitt oder einem festen Wert. Abendspitzen und eine wachsende Nutzerschaft lösen sie nicht mehr aus; eine Welle weit über alles, was der Client in der Woche hatte, weiterhin (REQ-XCH-007, REQ-XCH-028, ADR-0303).
