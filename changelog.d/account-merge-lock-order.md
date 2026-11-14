### Fixed

- **Konten-Zusammenführung ohne Deadlock.** Die Admin-Zusammenführung zweier Konten nimmt die Sperre der Auftrags-Geldkonten jetzt vor den beiden Kontozeilen, in derselben Reihenfolge wie jede Geldbuchung und ihre Benachrichtigung. Läuft gerade eine Geldbenachrichtigung für eines der Konten, scheitert die Zusammenführung nicht mehr an einem Datenbank-Deadlock (REQ-SEC-046).
