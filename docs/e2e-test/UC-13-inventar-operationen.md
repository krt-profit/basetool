# UC-13 — Inventar-Operationen (Ein-/Aus-/Umbuchen, Verkauf, Zuweisung)

|                |                                                                                                                                        |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-13                                                                                                                                  |
| **Tag**        | `e2e`                                                                                                                                  |
| **Testklasse** | [`InventoryOperationsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventoryOperationsE2eTest.java) |

## Akteur

Der synthetische Test-User `test-admin` (ADMIN, mit IRIDIUM-Mitgliedschaft). Getrieben wird die persönliche Lager-Ansicht `/inventory/my`, in der jeder Eintrag — unabhängig von der Rolle — Ausbuchen-, Umbuchen- und Verkaufen-Dialog sowie die Zuordnungs-Chips für Auftrag und Einsatz anbietet.

## Vorbedingungen

Pro Szenario wird **ein eigenes Material** plus zugehöriger Lagereintrag per REST geseedet, damit die Szenarien sich im gemeinsam genutzten Stack nicht gegenseitig stören (das Lager ist append-only — Umbuchen und Einbuchen legen neue Zeilen an, Ausbuchen/Verkauf dekrementieren). Geseedet werden zusätzlich:

- IRIDIUM-Mitgliedschaft (`ensureIridiumMembership`) und eine gemeinsame Quell-Location `E2E Inv Ops Hub` (`createLocation`).
- Die Bootstrap-Katalog-Location `E2E Refinery Hub` (`findLocationIdByName`) — sie liegt garantiert im 10-Minuten-Cache der Location-Lookup und wird daher im Umbuchen-Dialog vorausgewählt.
- Ein Einsatz (`createMission`) und mehrere Aufträge (`createJobOrder`), die die jeweiligen Zuweisungs-Materialien anfragen — darunter einer über 400 SCU mit Qualitätsuntergrenze 650, an den nie Bestand gebunden wird.
- Ein verkaufsfähiges Terminal (`seedSellableTerminal`): ein `terminal` + eine `material_price`-Zeile mit positivem Verkaufspreis, sodass der sonst deaktivierte „Verkauf"-Radiobutton aktiv wird.

## Auslöser

Der User öffnet `/inventory/my` (bzw. das Einbuchen-Formular `/inventory/input?source=my`) und löst die jeweilige Operation aus.

## Hauptablauf

### Die sechs Operationen

1. **Einbuchen** — `/inventory/input?source=my`: Material + Ort (aus dem Dropdown), Qualität und Menge eingeben, speichern.
2. **Ausbuchen (Teilmenge)** — 40 von 100 SCU als `DISCARD` ausbuchen.
3. **Ausbuchen (Vollmenge)** — die vollen 50 SCU ausbuchen → die Zeile fällt unter die Lösch-Epsilon und verschwindet.
4. **Umbuchen** — über den eigenen Umbuchen-Dialog (Modus `LOCATION`, seit #868 nicht mehr im Ausbuchen-Dialog) 30 von 100 SCU an eine andere Location. Der Dialog zeigt dabei den Einheiten-Picker vorbelegt mit der besitzenden Einheit der Zeile (REQ-INV-007, #1328).
5. **Verkaufen** — auf das freigeschaltete `SELL`-Radio warten, Terminal, Verkaufsbetrag und 30 von 80 SCU, absenden.
6. **Zuweisen zu einem Auftrag / Einsatz** (Variante C, REQ-INV-027) — am Eintrag „+ Zuordnen" öffnen, Auftrag bzw. Einsatz in der Combobox wählen, Menge eingeben, speichern (AJAX `POST /inventory/{id}/allocation`). Der Chip rendert ohne Reload aus dem zurückgegebenen DTO.

### Zuordnung und Herkunft

7. **Erneutes Wählen eines schon zugeordneten Auftrags** — die Option steht nach der eigenen Zuordnung noch in der Liste (der Picker wird nicht neu gerendert). Die Auswahl öffnet den bestehenden Anteil im Bearbeiten-Modus, vorbelegt mit seiner Menge; Speichern schickt einen `PATCH` statt eines doppelten `POST`.
8. **Herkunft-Picker, mehrdeutig** — 70 von 100 SCU sind einem Auftrag zugeordnet (Rest 30); beim Ausbuchen von 50 sperrt der Picker den Absenden-Button mit Warnung, bis 40 dem Auftrag zugewiesen sind.
9. **Herkunft-Picker, eindeutig** — die ganzen 60 SCU sind einem Einsatz zugeordnet (Rest 0); im Umbuchen-Dialog ist das Einsatz-Feld vorbelegt und gesperrt, folgt einer auf 40 gesenkten Menge und lässt den Absenden-Button aktiv.
10. **Restmenge an der Auftragsoption** (REQ-INV-039) — im Einbuchen-Formular trägt die Auftragsoption der Zuordnungszeile den offenen Bedarf (`· noch 400 SCU`); unterschreitet die eingegebene Qualität die Untergrenze des Auftrags, trägt sie zusätzlich einen Hinweis.

### Edge Cases und Ansichtszustand

11. Ausbuchen über den Bestand hinaus.
12. Umbuchen auf dieselbe Location und denselben Nutzer (No-op).
13. Ein persönlicher Eintrag mit Zuordnung: das Häkchen „persönlich" im Einbuchen-Formular blendet die Zuordnungszeilen aus und leert sie.
14. **Aufgeklappter Baum bleibt erhalten** (REQ-INV-002) — Materialgruppe und Stack aufklappen, in place ausbuchen; dieselbe Blattzeile ist danach ohne erneutes Aufklappen wieder sichtbar.

## Erwartetes Ergebnis

Bestandsänderungen werden über die UI getrieben und anschließend **über dieselbe gruppierte API verifiziert, die `/inventory/my` selbst nutzt** (`GET /api/v1/inventory/my-inventory/grouped?materialIds=…`): Einbuchen erhöht den Bestand um genau die eingegebene Menge (als Delta gemessen), Teil-Ausbuchen senkt ihn auf 60, volles Ausbuchen entfernt den Stack, Umbuchen hinterlässt zwei Stacks (70 Quelle / 30 Ziel), Verkauf senkt auf 50. Die Zuordnungen werden am gerenderten Chip geprüft, weil die gruppierte API die Zuordnung pro Eintrag seit Variante C nicht mehr trägt. Nach dem Herkunft-Ausbuchen hält die Zeile 50 und der Auftrags-Chip ist von 70 auf 30 geschrumpft; nach der vorbelegten Umbuchung ist der Einsatz-Chip von 60 auf 20 geschrumpft — die Menge kam also aus der gewählten Zuordnung und nicht still aus dem Rest. Die Edge Cases lassen den Bestand unverändert (Übermenge: Fehler-Toast ohne Navigation).

## Sonderfälle & Lehren

- **UI treiben, API verifizieren.** Die gruppierte Tree-Tabelle lädt Stack-Einträge **lazy** und gruppiert sie; gegen den gerade geschriebenen Zustand zu assertieren ist über die API robuster und rennt nie dem Post-Write-Render hinterher. Die gruppierte Abfrage liefert alle eigenen Zeilen unabhängig vom `personal`-Flag, daher tauchen die geseedeten nicht-persönlichen Zeilen dort auf.
- **Gecachte vs. ungecachte Lookups.** Material- und Location-Lookup sind 10 Minuten gecacht (`getCached`) — das Einbuchen wählt daher (wie UC-03) das, was das Dropdown anbietet, und liest die gewählte Id zur Verifikation zurück; das Umbuchen-Ziel-Dropdown ist ebenfalls gecacht, weshalb der No-op-Edge-Case seine Zeile an `E2E Refinery Hub` verankert. Auftrags- und Einsatz-Lookup sind **nicht** gecacht, daher erscheinen frisch geseedete sofort in der Zuordnungs-Combobox.
- **Verkauf ist UI-seitig gated.** Der `SELL`-Radiobutton bleibt deaktiviert, bis `GET /api/v1/materials/{id}/terminals` eine nicht-leere Liste liefert; das Backend speichert den Terminalnamen ohne FK-Prüfung. `seedSellableTerminal` legt Terminal + Preis per JDBC an (UEX-synced, über die Admin-API auf frischer DB nicht anlegbar — wie der Katalog-Seed).
- **Append-only + Epsilon.** Umbuchen/Einbuchen fügen Zeilen hinzu; DISCARD/SELL dekrementieren und löschen unterhalb von `1e-4`. Jedes Szenario nutzt ein eigenes Material zur Isolation.
- **Ein Login pro Klasse.** Der OIDC-Login (die dokumentierte Flakiness-Quelle der Suite) läuft einmal im `@BeforeAll`; jeder Test öffnet aus diesem Storage-State seinen eigenen Kontext.
- **SCU-Eingabe.** Das `data-scu-decimal`-Feld erzwingt nur „> 0", nicht das gehaltene Maximum, sodass die Übermengen-Buchung tatsächlich das Backend erreicht; das In-Place-Ausbuchen zeigt dessen Ablehnung als Toast.
- **Der veraltete Picker ist Absicht.** Der Test zum erneuten Wählen prüft ausdrücklich, dass die schon zugeordnete Option noch in der Liste steht — das ist seine Vorbedingung. Früher endete die Auswahl im doppelten `POST` und im 400 des Backends („ein Ziel höchstens einmal pro Eintrag und Dimension"), angezeigt als allgemeiner Fehler-Toast.
- **Persönlich + Zuordnung** lehnt auch das Backend ab (422); das deckt der Controller-Unit-Test ab, der E2E-Test prüft die UI-Seite.
