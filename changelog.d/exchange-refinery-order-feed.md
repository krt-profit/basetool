### Added

- **Verbundene Anwendungen: Raffinerieaufträge lesen.** Eine zugelassene Anwendung mit der
  Berechtigung `exchange.refinery.read` liest die eigenen Raffinerieaufträge des Mitglieds über
  `GET /exchange/v1/me/refinery-orders` – laufende und offene als Stand, danach jede Änderung,
  auch Abschluss, Abbruch und Löschung (REQ-XCH-051).
