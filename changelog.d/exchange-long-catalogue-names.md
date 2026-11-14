### Fixed

- **Exchange: lange Katalognamen brechen keine Antwort mehr.** `catalog/locations` und `catalog/resolve` kürzen den Namen eines Lagerorts, Gegenstands, Materials oder Schiffstyps auf die 200 Zeichen, die das v1-Schema erlaubt, wie es die Feeds schon tun. Ein längerer Name ließ bisher die ganze Antwort mit `502` scheitern (REQ-XCH-026).
- **Exchange: ein gekürzter Name findet seinen Lagerort wieder.** Schickt eine verbundene Anwendung den auf 200 Zeichen gekürzten Namen eines Lagerorts oder einer Raffineriemethode zurück, landet die Buchung beim richtigen Eintrag, sofern kein Eintrag genau so heißt und nur einer auf diesen Namen gekürzt wird (REQ-XCH-026).
