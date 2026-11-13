### Added

- **Patchwechsel.** Admins erfassen einen Star-Citizen-Patch einmal für alle Einheiten: Bezeichnung, Live-Zeit, Patch-Art und Optionen, mit Live-Vorschau, Bestätigung per Eingabe der Bezeichnung und Historie. Laufende Raffinerieaufträge, die vor der Live-Zeit gestartet wurden, werden dabei storniert; Auditbereich „Patchwechsel“ (REQ-PATCH-001…009).
- **Lager.** Bestand von vor dem letzten Patchwechsel wird nicht mehr mit neuen Buchungen zusammengeführt (REQ-PATCH-004).
- **Exchange.** Änderungen eines Patchwechsels tragen die Quelle `patch` mit Id und Bezeichnung; stornierte Raffinerieaufträge zeigen `canceledByPatch` (REQ-PATCH-007).
