### Changed

- **Raffinerie-API: `parts` ist Pflicht.** `PUT /api/v1/refinery-orders/{id}` verlangt die zu
  schreibenden Teile und deren Abschnittszähler; das Ersetzen des ganzen Auftrags und das Feld
  `version` entfallen (REQ-REFINERY-029).
