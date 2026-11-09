### Fixed

- **Raffinerie: Speichern, Abbrechen und Einlagern ohne falschen Konflikt.** Schreibt eine
  verbundene Anwendung oder ein zweiter Tab gleichzeitig einen anderen Abschnitt des Auftrags,
  scheitert die Aktion im Web nicht mehr mit einem Konflikt (REQ-REFINERY-029).
- **Raffinerie: Speichern ohne JavaScript speichert wieder.** Ein Absenden des Auftragsformulars,
  das das Seitenskript nicht abfängt, schreibt alle Abschnitte statt nichts (REQ-FE-006).
- **Raffinerie: Waren behalten ihre ID.** Eine Änderung von Zeit, Status oder Waren im Web ändert
  die vorhandenen Waren an Ort und Stelle, statt sie neu anzulegen; die IDs, die eine verbundene
  Anwendung hält, bleiben gültig (REQ-REFINERY-029).
- **Raffinerie: Gelöscht-Hinweis nur nach Rückfrage.** Die Detailseite sperrt sich erst, wenn der
  Server bestätigt, dass der Auftrag weg ist; ein Verbindungsabbruch, ein geöffneter Dialog oder ein
  fremdes Browser-Signal sperrt sie nicht mehr, und nach einem Rückgängig wird auch das
  Einlagern-Formular wieder frei (REQ-XCH-055).
- **Raffinerie: „via <Anwendung>" nur bei abgeschlossenen Aufträgen.** Ein wieder geöffneter oder
  abgebrochener Auftrag zeigt den Hinweis auf die abholende Anwendung nicht mehr
  (REQ-REFINERY-028).

### Changed

- **Raffinerie-API: `parts` ist Pflicht.** `PUT /api/v1/refinery-orders/{id}` verlangt die zu
  schreibenden Teile und deren Abschnittszähler; das Ersetzen des ganzen Auftrags und das Feld
  `version` entfallen (REQ-REFINERY-029).
