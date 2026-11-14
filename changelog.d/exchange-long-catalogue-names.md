### Fixed

- **Exchange: lange Katalognamen brechen keine Antwort mehr.** `catalog/locations` und `catalog/resolve` kürzen den Namen eines Lagerorts, Gegenstands, Materials oder Schiffstyps auf die 200 Zeichen, die das v1-Schema erlaubt, wie es die Feeds schon tun. Ein längerer Name ließ bisher die ganze Antwort mit `502` scheitern (REQ-XCH-026).
