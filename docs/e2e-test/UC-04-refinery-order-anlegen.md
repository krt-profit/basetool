# UC-04 — Refinery Order anlegen

|                |                                                                                                                                        |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-04                                                                                                                                  |
| **Tag**        | `e2e`                                                                                                                                  |
| **Testklasse** | [`RefineryOrderCreateE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RefineryOrderCreateE2eTest.java) |

## Akteur

Authentifizierter User mit IRIDIUM-Mitgliedschaft.

## Vorbedingungen

- Eingeloggte Session (UC-01).
- IRIDIUM-Mitgliedschaft geseedet (`ensureIridiumMembership`).
- Ein RAW-Eingangsmaterial geseedet (`createRefineryMaterial`, `isManualRawMaterial=true`).
- UEX-Katalog per JDBC geseedet (`seedCatalog`): eine refinery-fähige Location „E2E Refinery Hub" und eine Refining Method „E2E Refining Method".

## Auslöser

Der User öffnet das Refinery-Order-Anlegen-Formular `/refinery-orders/create`.

## Hauptablauf

1. Navigiere zu `/refinery-orders` und folge `refinery-create-link` nach `/refinery-orders/create`.
2. Falls das Owner-Feld editierbar ist (Logistician), wähle einen Owner; sonst übernimmt ein Hidden-Feld den Aufrufer.
3. Wähle Standort (`#locationId` → „E2E Refinery Hub") und Refining Method (`#refiningMethodId` → „E2E Refining Method").
4. Fülle die Goods-Zeile: Eingangsmaterial (`#inputMaterialId_0`), Eingangsmenge (`#inputQuantity_0`) **und** erwartete Ausgangsmenge (`#outputQuantity_0`).
5. Submit über `refinery-submit`.

## Erwartetes Ergebnis

Der Auftrag erscheint in der Liste unter `/refinery-orders` als `refinery-order-row`.

## Sonderfälle & Lehren

- **Drei Pflichtfelder in der Goods-Zeile:** `inputMaterialId_0`, `inputQuantity_0` **und** `outputQuantity_0` sind HTML-`required` (nicht nur Material + Ausgangsmenge). Fehlt eines, blockiert der Browser den Submit stumm.
- **Standort-Dropdown:** listet nur **refinery-fähige** Standorte (`getRefineryLocations` joint über `city.has_refinery`). Solche Standorte sind UEX-synced und per Admin-API nicht anlegbar → der Katalog-Snapshot setzt `has_refinery=true` per JDBC.
- `LocationDto` hat ein primitives `boolean hidden`, das beim POST mitgesendet werden muss (sonst scheitert die Jackson-Deserialisierung).
