### Fixed

- **Materialaufträge.** Eine Übergabe überschreibt die geforderte Menge nicht mehr. Jede Übergabe
  merkt sich, auf welche Materialzeile sie angerechnet wurde, und eine Überlieferung wird als
  Überschuss festgehalten. Die Auftragsseite zeigt pro Zeile „Gefordert“ und „Übergeben“.
- **Aufträge.** Bei Material-Aufträgen zeigt die Übersicht unter „Gefordert“ die bestellte Menge;
  der Fortschritt zählt Lagerbestand und bereits Übergebenes, ein komplett übergebener Auftrag
  steht auf 100 % statt auf 0 %.
- **Materialauftrag bearbeiten.** Das Formular zeigt die geforderte Gesamtmenge. Eine Zeile mit
  Übergaben kann nicht unter die übergebene Menge gesenkt, entfernt oder in eine andere
  Qualitätsstufe verschoben werden. Bei bestehenden Aufträgen wird die geforderte Menge aus Rest
  und Übergaben wiederhergestellt; eine frühere Überlieferung lässt sich dabei nicht mehr erkennen.
