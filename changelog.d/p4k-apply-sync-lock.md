### Fixed

- **P4K-Import: Anwenden wartet auf laufende Wiki-/UEX-Syncs.** Ein P4K-Anwenden läuft nie mehr
  gleichzeitig mit einem Wiki- oder UEX-Sync; hält ein Sync die Sperre zu lange, schlägt der Job mit
  einer Meldung fehl und ändert nichts (REQ-P4K-004).
