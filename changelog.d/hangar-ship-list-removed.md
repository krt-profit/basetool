### Fixed

- **Hangar-Schnittstelle.** Die ungenutzte Schiffsliste `GET /api/v1/hangar/ships` ist entfernt. Sie
  lieferte Besitzer, Standort und Einsatzbereitschaft jedes Schiffs der eigenen Org-Einheiten ohne
  das Offiziersrecht, das die Org-Einheitsübersicht dafür verlangt.
