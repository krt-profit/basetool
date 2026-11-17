### Fixed

- **Updates.** Ein Update, das einen Dienst umbenennt oder entfernt, nimmt die Anwendung erst im
  eigentlichen Neustart vom Netz statt schon während des Herunterladens. Scheitert es vorher, läuft
  die bisherige Version ungestört weiter, und ein zurückgerolltes Update startet wieder alle Dienste
  der bisherigen Version.
- **Updates.** Fehlt auf dem Server ein Verzeichnis oder eine Datei, die ein neuer Dienst braucht,
  lehnt die Auslieferung das Update ab, bevor sie etwas ändert.
