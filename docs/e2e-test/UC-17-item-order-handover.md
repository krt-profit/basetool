# UC-17 — Item-Auftrag anlegen & Item-Handover

|                |                                                                                                                                          |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-17                                                                                                                                    |
| **Tag**        | `e2e`                                                                                                                                    |
| **Testklasse** | [`JobOrderItemHandoverE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderItemHandoverE2eTest.java) |
| **Basis-Flow** | [UC-03](UC-03-job-order-anlegen.md) · Item-Anlegen analog [UC-03](UC-03-job-order-anlegen.md)                                            |

## Akteur

Authentifizierter User mit der Rolle LOGISTICIAN/OFFICER/ADMIN — hier `test-admin`.

## Vorbedingungen

- IRIDIUM-Mitgliedschaft (`ensureIridiumMembership`).
- Ein **bestellbares Item** (`game_item` + aktiver Blueprint mit aufgelöster RESOURCE-Zutat) — beim Stack-Bootstrap geseedet, **bevor** der erste `/orders/create`-Aufruf den 10-min-Item-Katalog-Cache füllt; sonst ist der Item-Picker leer.

## Auslöser

Der User legt unter `/orders/create` im Item-Modus einen Auftrag über **2 Stück** an und protokolliert anschließend Item-Übergaben auf der Detailseite.

## Hauptablauf

1. `/orders/create` → Item-Modus (`order-mode-item`), bearbeitende + anfragende Einheit wählen, Item aus der Such-Combobox (`order-item-combobox`) picken, auf die Material-Qualitätssteuerung warten, Menge = `2`, absenden (`order-item-submit`).
2. Auftrags-Id per Admin-Read-Back (`findOrderByHandle`) auflösen, `/orders/{id}` öffnen → `item-handover-open` sichtbar (2 ausstehend).
3. **Teil-Übergabe:** `item-handover-open` → Modal → `entries[0].amount` = `1` → `item-handover-submit`. Die Übergabe erscheint als `item-handover-row`; `item-handover-open` **bleibt** sichtbar (1 ausstehend).
4. **Edit-Freeze:** `GET /orders/{id}/items/edit` leitet auf die Detailseite zurück (es existiert eine Übergabe) — die Edit-Form (`order-item-submit`) erscheint nicht.
5. **Rest-Übergabe** (1 Stück) → der Auftrag wird automatisch abgeschlossen, `item-handover-open` verschwindet.

## Erwartetes Ergebnis

Die ausstehende Menge sinkt pro Übergabe um die gelieferte Stückzahl; nach voller Lieferung ist der Auftrag `COMPLETED` und der Item-Übergabe-Button weg. Ab der ersten Übergabe sind die Item-Zeilen eingefroren (kein Edit mehr).

## Sonderfälle & Lehren

- **Eine Zeile pro ausstehender Position:** Das Modal rendert je Position mit `outstanding > 0` ein Number-Feld `entries[i].amount` (HTML5-`max` = ausstehende Menge). Der Controller verwirft leere/0-Zeilen.
- **Auto-Complete:** Sobald jede Position vollständig geliefert ist, schließt der Backend den Auftrag (`completeJobOrderWithinTransaction`, Propagation `MANDATORY`) und trennt verknüpftes Inventar. Der Button hängt am Flag `hasOutstandingItemLines`.
- **Item-Edit nur vor der ersten Übergabe:** `viewEditItemForm` blockt, sobald `itemHandovers` nicht leer ist (Phase 3) — die Zeilen sind dann gefroren.
- **Item-Picker = Such-Combobox:** `krtSearchableSelect` lädt die Optionen per Live-Suche (`/orders/item-search`); der Test öffnet die Combobox, nimmt die erste Option und wartet, bis Blueprint + Derivation die Qualitätssteuerung gerendert haben. Eine Mengenänderung löst eine Re-Derivation aus, auf die erneut gewartet wird.
- **Übergabezeit:** Datum/Zeit-Split-Felder (`#item-handover-modal .date-part` / `.time-part`) syncen in das versteckte `#itemHandoverTime` (UTC-ISO); Vergangenheit ist hier erlaubt.

