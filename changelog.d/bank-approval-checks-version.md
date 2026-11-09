### Fixed

- **Bank: Freigaben gelten nur für den gelesenen Stand.** „Freigabe erteilen" schickt den Stand des
  Antrags mit; wurde er inzwischen korrigiert, lehnt die Bank die Freigabe mit einem Konflikt ab,
  statt einen geänderten Betrag freizugeben (REQ-BANK-056).
