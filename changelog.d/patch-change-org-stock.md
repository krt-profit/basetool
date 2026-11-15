### Changed

- **Gemeinsames Lager gegen den Patchwechsel geschrieben.** Schreibt eine verbundene Anwendung auf einen Eintrag im gemeinsamen Lager einer Einheit, den ein Patchwechsel gelöscht hat, erhält sie `REMOVED_ELSEWHERE` mit der Kennung des Patchwechsels statt `NOT_PERMITTED`. Hat der Umzug den Eintrag verschoben, nennt der `VERSION_CONFLICT` den Patchwechsel ebenfalls. Maßgeblich ist die letzte Änderung genau dieses Eintrags.
