# UC-30 — Item-Lager-Operationen (Einbuchen, Um-/Ausbuchen, Zuordnungs-Gate, Live-Sync)

|                |                                                                                                                                                |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-30                                                                                                                                          |
| **Tag**        | `e2e`                                                                                                                                          |
| **Testklasse** | [`ItemInventoryOperationsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ItemInventoryOperationsE2eTest.java) |

## Akteur

Authentifizierter User mit der Rolle LOGISTICIAN, OFFICER oder ADMIN (IRIDIUM-Mitgliedschaft). Getrieben werden das Einbuchen-Formular im Item-Modus (`/inventory/input`), die Item-Ansicht des persönlichen Lagers (`/inventory/my?view=items`) und die geteilte Item-Ansicht (`/inventory/all?view=items`).

## Vorbedingungen

Buchbare Items sind der Output mindestens eines aktiven Blueprints (REQ-INV-029); die UEX-/SCWiki-Syncs laufen im E2E-Stack nie, daher wird der Katalog pro Szenario geseedet:

- Je Szenario **ein eigenes Game-Item** über `seedOrderableItem` (per JDBC: `game_item` + aktiver `blueprint` + aufgelöste RESOURCE-Zutat — dieselbe Minimalform, die der Stack-Bootstrap einmalig für den Item-Order-Picker anlegt).
- Item-Bestandszeilen für die Modal-/Live-Sync-Flows über das echte `POST /api/v1/inventory` mit `gameItemId`-Payload (`createItemInventoryEntry`).
- Für das Zuordnungs-Gate ein qualifizierender ITEM-Auftrag (`createItemJobOrder` — Blueprint über den Orderable-Katalog aufgelöst) plus ein MATERIAL-Auftrag als Negativfall.
- IRIDIUM-Mitgliedschaft (`ensureIridiumMembership`) und eine gemeinsame Quell-Location (`createLocation`).

## Auslöser

Der User öffnet das Einbuchen-Formular im Item-Modus bzw. eine der beiden Item-Ansichten und löst die jeweilige Operation aus.

## Hauptablauf

1. **Einbuchen (Item-Modus)** — Katalog-Toggle auf „Item", Item über die remote-gesuchte Combobox wählen, Ort + ganze Menge, absenden; danach ist die neue Zeile im Item-Baum (`Item → Stack → Eintrag`) erreichbar.
2. **Umbuchen + Ausbuchen** — Item-Baum zum Eintrag aufklappen (dabei: **keine** Qualitätsspalte, **kein** Einsatz-Split — Locator-Abwesenheit), 20 Einheiten per Umbuchen-Modal an eine andere Location transferieren (Quelle behält 30, Ziel erhält 20), dann die restlichen 30 als DISCARD ausbuchen — der Quell-Stack verschwindet.
3. **Zuordnungs-Gate (REQ-INV-031)** — im Item-Modus mit gewähltem Item bietet die „+ Auftrag"-Zeile nur ITEM-Aufträge an, deren Zeilen das Item anfragen (`data-game-items`-CSV-Filter); die Option des MATERIAL-Auftrags ist deaktiviert, der Einsatz-Abschnitt ist komplett ausgeblendet.
4. **Live-Peer-Sync (REQ-FE-010/015)** — zwei Kontexte auf `/inventory/all?view=items` mit aufgeklapptem Widget; Kontext B bucht 40 von 100 Einheiten aus, Kontext A zeigt die reduzierte Gruppensumme **in place** (einziger `inventory`/`stock`-Seam, `view=` reitet auf der Fragment-URL) — ohne Reload.

## Erwartetes Ergebnis

Jede Mutation läuft über die echte UI und wird über dieselbe gruppierte `catalog=ITEM`-API verifiziert, die die Item-Ansichten selbst rendern (`GET /api/v1/inventory/my-inventory/grouped?catalog=ITEM&gameItemIds=…`): das Einbuchen erhöht den Bestand um die ganze Menge, das Umbuchen erzeugt Quell- und Ziel-Stack (30/20), das volle Ausbuchen entfernt den Quell-Stack. Item-Zeilen rendern weder Qualität noch Einsatz-Zuordnung; das Zuordnungs-Gate lässt nur qualifizierende ITEM-Aufträge zu; der Peer sieht die Änderung live ohne Reload.

## Sonderfälle & Lehren

- **Gecachte vs. ungecachte Kataloge.** Der Item-Picker ist ein Remote-Search (`remote-game-items` → `/inventory/item-search`, ungecacht) — frisch geseedete Items erscheinen sofort. Die Location-Comboboxen bleiben 10 Minuten gecacht, daher wählen die Flows, was das Dropdown anbietet (wie UC-13).
- **Item-Semantik.** Item-Zeilen sind katalog-diskriminiert (`gameItemId` XOR `materialId`, REQ-INV-029): ganze Mengen, keine Qualität, kein Einsatz-Split, PIECE-Auto-Merge; die geteilten Modals (Ausbuchen/Umbuchen) funktionieren unverändert über `data-game-item-id`.
- **Ein Seam, drei Spiegelpunkte.** Der Live-Sync nutzt den bestehenden `inventory`/`stock`-Seam ohne neue Section-Keys; der Empfänger zieht sein eigenes gefiltertes Fragment inklusive `view=items` (REQ-FE-010-Paritätsregel bleibt unberührt).
- **UI treiben, API verifizieren** — wie UC-13: die Baum-Ansicht lädt Stack-Einträge lazy, Assertions gegen die gruppierte API rennen dem Post-Write-Render nie hinterher.

