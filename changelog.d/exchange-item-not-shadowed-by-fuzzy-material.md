### Fixed

- **Exchange-API: Items nicht mehr fälschlich mehrdeutig.** Ein Item wie `CoverAll` oder `Mirage`
  wird in Lager- und Org-Lager-Änderungen nicht mehr als `AMBIGUOUS` abgelehnt, nur weil ein
  Materialname ähnlich klingt; ein exakter Item-Treffer geht jedem unscharfen Materialvorschlag vor.
