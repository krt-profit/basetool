### Fixed

- **Katalog-Abgleich: P4K-Identität bleibt erhalten.** Die automatische Zusammenführung doppelter
  Wiki-/UEX-Items führt zwei Items mit verschiedenen P4K-GUIDs nicht mehr zusammen, sondern legt sie
  der P4K-Prüfliste vor; eine P4K-GUID des aufgelösten Eintrags wandert auf den verbleibenden, und
  dessen P4K-Name wird nicht mehr vom UEX-Namen überschrieben (REQ-P4K-007).
