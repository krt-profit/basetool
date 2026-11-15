### Fixed

- **Währungs-Wipe nach einem Wipe-Reset.** Wurde die Kartellbank zwischen der Live-Zeit eines Patchwechsels und seiner Erfassung schon per Wipe-Reset auf null gesetzt, bucht der Währungs-Wipe nichts mehr aus: Ein- und Auszahlungen nach diesem Reset bleiben auf Konten und Haltern stehen, und kein Konto zählt als bei null gestoppt (REQ-PATCH-041, ADR-0384).
