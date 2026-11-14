### Added

- **Patchwechsel: Währungs-Wipe.** Die Option setzt die Kartellbank auf ihre Salden zur Live-Zeit zurück, spätere Buchungen bleiben; offene Bankanträge und Bankvorgänge von Aufträgen von davor werden storniert, und ein stornierter Bankantrag nennt unter seinem Status den Patchwechsel als Grund. aUEC-Bestände, die es zur Live-Zeit gab, werden als Fehlbetrag mit der Patch-Bezeichnung ausgebucht, Zusagen von davor enden; Vorschau und Auditereignisse inklusive (REQ-PATCH-040…046, ADR-0384).
