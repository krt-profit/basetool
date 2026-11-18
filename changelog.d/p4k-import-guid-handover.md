### Fixed

- **P4K-Import, Gegenstände und Hersteller.** Ein Import bricht nicht mehr mit einem doppelten
  `p4k_uuid` ab, wenn eine Zeile zu dem Datensatz umzieht, den ihre SC-Wiki-UUID oder ihr
  Klassenname nennt, und eine andere Zeile ihre bisherige GUID übernimmt. Kommt der Umzug nicht
  zustande, behält die Zeile ihre GUID.
