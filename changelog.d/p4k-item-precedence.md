### Changed

- **Katalog: Gegenstände folgen dem P4K.** Ein P4K-Import setzt Name, Hersteller, Typ, Größe, Grad,
  Klasse und Masse eines Gegenstands; das SC Wiki füllt nur noch Lücken, und eine abweichende
  Wiki-Angabe erscheint im Sync-Bericht als „Weicht vom P4K ab" (Migration V346, REQ-P4K-017…019).
- **Grad aus dem Spielarchiv.** Der Buchstabe A–D kommt nur für Kühler, Kraftwerke, Quantenantriebe,
  Radare und Schilde aus dem P4K, sonst weiter aus dem Wiki; unbekannte Grade zählt
  `basetool_catalogue_item_grade_unknown_total`.
