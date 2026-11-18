### Fixed

- **Überwachung.** `LogStreamSilent` meldet eine ruhige Logdatei wie `fail2ban.log` nach einem
  Neustart von Alloy nicht mehr als „nicht getailt“. Die Regel prüft jetzt, ob Alloy die Datei
  liest, statt ob seit dem Neustart eine neue Zeile kam.
