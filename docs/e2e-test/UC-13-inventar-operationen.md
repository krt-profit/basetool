# UC-13 — Inventar-Operationen (Ein-/Aus-/Umbuchen, Verkauf, Zuweisung)

|                |                                                                                                                                        |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-13                                                                                                                                  |
| **Tag**        | `e2e`                                                                                                                                  |
| **Testklasse** | [`InventoryOperationsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventoryOperationsE2eTest.java) |

## Akteur

Authentifizierter User mit der Rolle LOGISTICIAN, OFFICER oder ADMIN (IRIDIUM-Mitgliedschaft). Getrieben wird die persönliche Lager-Ansicht `/inventory/my`, in der jeder Eintrag — unabhängig von der Rolle — Ausbuchen-Button und Auftrag-/Einsatz-Zuordnung anbietet.

## Vorbedingungen

Pro Szenario wird **ein eigenes Material** plus zugehöriger Lagereintrag per REST geseedet, damit die Szenarien sich im gemeinsam genutzten Stack nicht gegenseitig stören (das Lager ist append-only — Umbuchen und Einbuchen legen neue Zeilen an, Ausbuchen/Verkauf dekrementieren). Geseedet werden zusätzlich:

- IRIDIUM-Mitgliedschaft (`ensureIridiumMembership`) und eine gemeinsame Quell-Location (`createLocation`).
- Die Bootstrap-Katalog-Location `E2E Refinery Hub` (`findLocationIdByName`) — sie liegt garantiert im 10-Minuten-Cache der Location-Lookup und wird daher im Umbuchen-Dialog vorausgewählt.
- Ein Einsatz (`createMission`) und ein Auftrag (`createJobOrder`), der das Zuweisungs-Material anfragt.
- Ein verkaufsfähiges Terminal (`seedSellableTerminal`): ein `terminal` + eine `material_price`-Zeile mit positivem Verkaufspreis, sodass der sonst deaktivierte „Verkauf"-Radiobutton aktiv wird.

## Auslöser

Der User öffnet `/inventory/my` (bzw. das Einbuchen-Formular `/inventory/input?source=my`) und löst die jeweilige Operation aus.

## Hauptablauf

Je ein Testfall pro Operation:

1. **Einbuchen** — `/inventory/input?source=my`: Material + Ort (aus dem Dropdown), Qualität und Menge eingeben, speichern.
2. **Ausbuchen (Teilmenge)** — Stack aufklappen, Ausbuchen-Button, Art `DISCARD`, Teilmenge, absenden.
3. **Ausbuchen (Vollmenge)** — wie 2., aber die volle Menge → die Zeile fällt unter die Lösch-Epsilon und verschwindet.
4. **Umbuchen** — Art `TRANSFER`, eine andere Ziel-Location wählen, absenden → Quelle bleibt, neue Zielzeile entsteht.
5. **Verkaufen** — auf das (per Terminals-`fetch`) freigeschaltete `SELL`-Radio warten, Terminal + Verkaufsbetrag + Menge, absenden.
6. **Zuweisen zu einem Auftrag** — Inline-`Auftrag`-Select der Eintragszeile ändern (AJAX `PUT /inventory/{id}/update-associations`).
7. **Zuweisen zu einem Einsatz** — Inline-`Einsatz`-Select analog.

Edge Cases: Ausbuchen über den Bestand hinaus, Umbuchung auf dieselbe Location/denselben Nutzer (No-op), persönlicher Eintrag mit Zuordnung.

## Erwartetes Ergebnis

Jede Operation wird über die UI getrieben und anschließend **über dieselbe gruppierte API verifiziert, die `/inventory/my` selbst nutzt** (`GET /api/v1/inventory/my-inventory/grouped?materialIds=…`): das Einbuchen erhöht den Materialbestand um die eingegebene Menge, Ausbuchen/Verkauf senken ihn, das volle Ausbuchen entfernt den Stack, das Umbuchen erzeugt zwei Stacks (70 Quelle / 30 Ziel), die Zuweisung setzt `jobOrderId` bzw. `missionId` des Stacks. Die Edge Cases lassen den Bestand unverändert bzw. re-rendern das Formular mit Feldfehler.

## Sonderfälle & Lehren

- **UI treiben, API verifizieren.** Die gruppierte Tree-Tabelle lädt Stack-Einträge **lazy** und gruppiert sie; gegen den gerade geschriebenen Zustand zu assertieren ist über die API robuster und rennt nie dem Post-Write-Render hinterher. Die gruppierte Abfrage liefert alle eigenen Zeilen unabhängig vom `personal`-Flag, daher tauchen die geseedeten nicht-persönlichen Zeilen dort auf.
- **Gecachte vs. ungecachte Lookups.** Material- und Location-Lookup sind 10 Minuten gecacht (`getCached`) — das Einbuchen wählt daher (wie UC-03) das, was das Dropdown anbietet, und liest die gewählte Id zur Verifikation zurück; das Umbuchen-Ziel-Dropdown ist ebenfalls gecacht, weshalb der No-op-Edge-Case seine Zeile an `E2E Refinery Hub` verankert. Auftrags- und Einsatz-Lookup sind **nicht** gecacht, daher erscheinen frisch geseedete sofort in den Zuordnungs-Selects.
- **Verkauf ist UI-seitig gated.** Der `SELL`-Radiobutton bleibt deaktiviert, bis `GET /api/v1/materials/{id}/terminals` eine nicht-leere Liste liefert; das Backend speichert den Terminalnamen ohne FK-Prüfung. `seedSellableTerminal` legt Terminal + Preis per JDBC an (UEX-synced, über die Admin-API auf frischer DB nicht anlegbar — wie der Katalog-Seed).
- **Append-only + Epsilon.** Umbuchen/Einbuchen fügen Zeilen hinzu; DISCARD/SELL dekrementieren und löschen unterhalb von `1e-4`. Jedes Szenario nutzt ein eigenes Material zur Isolation.
- **Ein Login pro Klasse.** Der OIDC-Login (die dokumentierte Flakiness-Quelle der Suite) läuft einmal im `@BeforeAll`; jeder Test öffnet aus diesem Storage-State seinen eigenen Kontext.
- **SCU-Eingabe.** Das `data-scu-decimal`-Feld erzwingt nur „> 0", nicht das gehaltene Maximum, sodass die Übermengen-Buchung tatsächlich das Backend (400) erreicht.
