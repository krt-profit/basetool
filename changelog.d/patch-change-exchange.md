### Added

- **Patchwechsel für verbundene Anwendungen.** Mit der Fähigkeit `exchange.patch.read` liest eine verbundene Anwendung wie VerseKit den letzten Patchwechsel mit seinen Optionen, der Wipe-Liste samt Katalogtypen und Ausnahmen sowie deine Home-Location-Abfrage mit Vorschau; mit `exchange.patch.write` wählt sie deine neue Home Location nach denselben Regeln wie im Basetool und löst den Umzug aus.
- **Gegen den Patchwechsel geschrieben.** Schreibt eine Anwendung auf einen Lagerposten oder ein Schiff, das ein Patchwechsel verschoben oder gelöscht hat, erhält sie `REMOVED_ELSEWHERE` mit der Kennung des Patchwechsels und übernimmt das Ergebnis, statt nachzufragen. Ist der Posten nur zum Teil umgezogen oder das Schiff verschoben, nennt auch der `VERSION_CONFLICT` den Patchwechsel.
