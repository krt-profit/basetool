### Added

- **Patchwechsel.** Admins erfassen einen Star-Citizen-Patch einmal für alle Einheiten: Bezeichnung, Live-Zeit, Patch-Art und Optionen, mit Live-Vorschau, Bestätigung per Eingabe der Bezeichnung und Historie. Laufende Raffinerieaufträge, die vor der Live-Zeit gestartet wurden, werden dabei storniert; Auditbereich „Patchwechsel“ (REQ-PATCH-001…009).
- **Lager.** Solange der Umzug eines Mitglieds aussteht, wird sein Bestand von vor dem Live-Gang nicht mit neuen Buchungen zusammengeführt; danach wieder wie gewohnt (REQ-PATCH-004).
- **Exchange.** Änderungen eines Patchwechsels tragen die Quelle `patch` mit Id und Bezeichnung; stornierte Raffinerieaufträge zeigen `canceledByPatch` (REQ-PATCH-007).
