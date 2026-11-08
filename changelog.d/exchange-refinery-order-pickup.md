### Added

- **Verbundene Anwendungen: Raffinerieaufträge abholen.** Eine Anwendung mit
  `exchange.refinery.write` meldet über die Operation `pickup`, dass ein offener oder laufender
  Auftrag im Spiel abgeholt wurde – auch vor Ablauf der Laufzeit. Der Auftrag wird abgeschlossen und
  erscheint live als „Abgeholt via <Anwendung>"; eingelagert wird nichts, und das Rückgängig-Machen
  öffnet ihn wieder (REQ-XCH-053, REQ-XCH-056).
