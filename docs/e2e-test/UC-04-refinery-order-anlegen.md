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
2. Falls die Owner-Combobox (`#ownerId`) sichtbar ist (Logistician), wähle ihre erste Option; sonst übernimmt ein Hidden-Feld den Aufrufer.
3. Wähle Standort (`#locationId` → „E2E Refinery Hub") und Refining Method (`#refiningMethodId` → „E2E Refining Method").
4. Fülle die Goods-Zeile: Eingangsmaterial über die Combobox `#inputMaterialId_0`, Eingangsmenge (`#inputQuantity_0`) **und** erwartete Ausgangsmenge (`#outputQuantity_0`).
5. Submit über `refinery-submit`.

### Tastatur-Auswahl füllt das Ausgangsmaterial (Regression)

6. `/refinery-orders/create` öffnen, in der Eingangsmaterial-Combobox den Namen eines geseedeten Rohmaterials tippen, bis genau ein Treffer übrig ist, und ihn mit **Enter** übernehmen — ohne die Zeile anzuklicken.

## Erwartetes Ergebnis

- Der Auftrag erscheint in der Liste unter `/refinery-orders` als `refinery-order-row`.
- Nach der Tastatur-Auswahl trägt die Combobox den Materialnamen, und die schreibgeschützte Anzeige `#outputMaterialDisplay_0` zeigt das raffinierte Ausgangsmaterial — genau wie nach einer Mausauswahl.

## Sonderfälle & Lehren

- **Drei Pflichtfelder in der Goods-Zeile:** `inputMaterialId_0`, `inputQuantity_0` **und** `outputQuantity_0` sind HTML-`required` (nicht nur Material + Ausgangsmenge). Fehlt eines, blockiert der Browser den Submit stumm.
- **Standort-Dropdown:** listet nur **refinery-fähige** Standorte. Seit REQ-REFINERY-020 (V226) joint `LocationRepository.findLocationsWithRefinery()` über das **abgeleitete** Flag `has_refinery_terminal` von Stadt bzw. Raumstation, nicht mehr über UEX' rohes `has_refinery`. Solche Standorte sind UEX-synced und per Admin-API nicht anlegbar, und im E2E-Stack läuft kein UEX-Sweep, der das Flag setzte → der Katalog-Snapshot (`uex-catalog-seed.sql`) setzt beide Flags und legt das passende Refinery-Terminal an.
- **Tastatur-Commit ohne Metadaten:** Die gerenderte Zeilenliste der Combobox trug früher nur Wert und Beschriftung jeder Option. Die Tastatur-Pfade spiegelten deshalb eine Option ohne `data-refined-name` auf das Hidden-Input, und `updateOutputMaterial` ließ die Ausgabe beim Platzhalter „-" stehen, obwohl das Eingangsmaterial korrekt gewählt war.
- `LocationDto` hat ein primitives `boolean hidden`, das beim POST mitgesendet werden muss (sonst scheitert die Jackson-Deserialisierung).
