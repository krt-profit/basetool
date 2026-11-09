### Added

- **Verbundene Anwendungen: Raffinerieaufträge schreiben.** Eine zugelassene Anwendung mit
  `exchange.refinery.write` legt die eigenen Raffinerieaufträge des Mitglieds über
  `POST /exchange/v1/me/refinery-orders/changes` an und ändert Ort, Methode, Laufzeit, Status,
  Kosten und Güter – geprüft gegen die Zähler je Abschnitt, mit Live-Aktualisierung der
  Raffinerie-Seiten und Rückgängig-Machen auf „Verbundene Anwendungen" (REQ-XCH-052, REQ-XCH-056).
- **Audit:** neues Ereignis `REFINERY_ORDER_DELETED`, wenn ein Rückgängig-Machen einen von der
  Anwendung angelegten Auftrag wieder entfernt.
