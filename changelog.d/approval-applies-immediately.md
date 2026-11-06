### Fixed

- **Freigabe wirkt sofort.** Ein frisch freigegebenes Mitglied, das während der Wartezeit
  angemeldet war, sah nach dem Login „Dashboard konnte nicht geladen werden“ statt der
  Nutzungsbedingungen, bis es einige Minuten später neu lud. Das Backend speichert den Status
  „wartet auf Freigabe“ nicht mehr im Berechtigungs-Cache zwischen (REQ-SEC-056).
