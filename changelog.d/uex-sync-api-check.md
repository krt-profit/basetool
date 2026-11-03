### Fixed

- **Schiffsdaten.** Kleine Fahrzeuge mit weniger als 1 SCU Laderaum (z. B. ATLS GEO mit 0,8 SCU) werden nicht mehr auf 0 SCU abgerundet und erscheinen jetzt in der Profitrechnung.
- **UEX-Sync.** Vergibt UEX die ID eines Gegenstands neu, bleibt der Gegenstand über seine UUID mit dem richtigen Eintrag verbunden, statt die Daten eines anderen Gegenstands zu übernehmen.
