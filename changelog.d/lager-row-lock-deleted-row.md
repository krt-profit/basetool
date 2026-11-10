### Fixed

- **Lager.** Sammel-Ausbuchen, Massen-Umbuchen, „Gestohlen" markieren, Org-Einheit- und
  Pool-Wechsel, eine Material-Übergabe, eine Produktionsbuchung und eine Spendenzusage auf eine
  Lagerzeile, die gleichzeitig gelöscht wird, antworten mit „nicht gefunden" statt mit einem
  Serverfehler. Der Empfang einer Spende aus einer solchen Zeile bucht die Menge in den Pool.
