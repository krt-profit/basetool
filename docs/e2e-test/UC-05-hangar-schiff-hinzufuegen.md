# UC-05 — Schiff zum Hangar hinzufügen

|                |                                                                                                                            |
|----------------|----------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-05                                                                                                                      |
| **Tag**        | `e2e`                                                                                                                      |
| **Testklasse** | [`HangarAddShipE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/HangarAddShipE2eTest.java) |

## Akteur

Authentifizierter User mit IRIDIUM-Mitgliedschaft.

## Vorbedingungen

- Eingeloggte Session (UC-01).
- IRIDIUM-Mitgliedschaft geseedet (`ensureIridiumMembership`) — Schiffe sind staffel-scoped.
- UEX-Katalog per JDBC geseedet (`seedCatalog`): ein ShipType „E2E Ship Type".

## Auslöser

Der User öffnet im Hangar den Dialog „Schiff hinzufügen".

## Hauptablauf

1. Navigiere zu `/hangar`.
2. Öffne den Dialog über `hangar-add-ship`.
3. Wähle den Schiffstyp (`#ship-type` → „E2E Ship Type") und die Versicherung (`#ship-insurance` → „LTI").
4. Setze den Marker `window.__krtNoReload`, blende den `position:fixed`-Footer aus und speichere über `hangar-ship-submit`; warte auf die Antwort des `POST /hangar/add`.

## Erwartetes Ergebnis

- Die Seite hat nicht neu geladen (der Marker ist noch gesetzt).
- Das Schiff erscheint **in place** in der Hangar-Liste als `hangar-ship-row` mit dem gewählten Schiffstyp — die Tabelle wird per `krtFetch` neu eingesetzt, ohne Navigation (#578, REQ-FE-001).

## Sonderfälle & Lehren

- **ShipTypes sind UEX-synced** (kein POST-Endpunkt, praktisch DB-Insert-only). Deshalb wird der Schiffstyp über den JDBC-Katalog-Snapshot geseedet statt über die Admin-API.
- Der Add-Ship-Dialog wird per JS geöffnet; die Selects (`#ship-type`, `#ship-insurance`) sind server-gerendert.
- **Footer ausblenden:** Der fixierte `.krt-footer` kann auf WebKit den vertrauenswürdigen Klick auf den Speichern-Button abfangen.
