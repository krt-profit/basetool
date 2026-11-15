### Fixed

- **Home-Location-Abfrage ohne gepflegte Home Location.** Ein Patchwechsel mit Home-Location-Abfrage lässt sich nur auslösen, wenn mindestens eine Home Location gepflegt ist. Verschwindet die letzte während einer laufenden Abfrage, erreicht ein Admin trotz Abfrage die Patchwechsel-Seite zum Beenden und die Pflege der Locations (REQ-PATCH-063).
- **Wahl gehört zur angezeigten Abfrage.** Die Wahl der Home Location nennt die Abfrage, die die Seite zeigte; hat inzwischen ein neuer Patchwechsel neu gefragt, wird sie mit `409 HOME_LOCATION_PHASE_CHANGED` abgelehnt und die Abfrage neu geladen, im Basetool wie bei verbundenen Anwendungen (Pflichtfeld `patchChange`, REQ-PATCH-062).
- **Umzugssperre für Zweckbindungen.** Andere Mitglieder können die Auftrags- und Missions-Zweckbindungen eines gesperrten Lagerpostens nicht mehr ändern oder entfernen (REQ-PATCH-023).
- **Sperre und Patchwechsel ohne Lücke.** Schreibvorgänge, die die Umzugssperre prüfen, lesen die Abfrage frisch und können nicht mehr mit einem gleichzeitigen Patchwechsel überkreuzen; während ein Patchwechsel eingetragen wird, lehnen sie mit 409 ab (REQ-PATCH-064).
