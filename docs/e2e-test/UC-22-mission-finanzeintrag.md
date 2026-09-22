# UC-22 — Einsatz: Finanzeintrag anlegen & Detailseite erneut öffnen

|                |                                                                                                                                        |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-22                                                                                                                                  |
| **Tag**        | `e2e`                                                                                                                                  |
| **Testklasse** | [`MissionFinanceEntryE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionFinanceEntryE2eTest.java) |

## Akteur

Authentifizierter User (Mitglied-oder-höher) mit Mitgliedschaft in der IRIDIUM-Staffel — hier `test-admin`. Das Finanz-Panel wird nur für Mitglied-oder-höher geladen.

## Vorbedingungen

Regressionsschutz für den Produktions-500: `GET /missions/{id}` warf eine Thymeleaf-`TemplateProcessingException`, sobald ein Einsatz mindestens einen Finanzeintrag besaß (Fix in PR #509, Unit-Regression in `MissionPageControllerMvcTest`). Per REST geseedet (nur ephemerer Modus):

- IRIDIUM-Mitgliedschaft (`ensureIridiumMembership`) — Einsätze sind staffel-scoped.
- Ein eigener Einsatz (`createMission`) und **ein externer Teilnehmer** (`addExternalParticipant`), weil das Finanz-Modal eine Teilnehmer-Auswahl (`required`) erzwingt.

## Auslöser

Der User öffnet die Einsatz-Detailseite `/missions/{id}` und darin im Finanz-Panel den „Neuer Eintrag"-Dialog.

## Hauptablauf

1. Detailseite direkt auf dem Finanzen-Tab öffnen (`/missions/{id}?tab=fin`); den Öffnen-Trigger des Finanz-Modals klicken (`button[data-trigger='open-modal-display'][data-modal-id='finance-modal']`).
2. Im Modal `#finance-modal`: in der Teilnehmer-Combobox die erste Option wählen (der geseedete externe Teilnehmer; der Leer-Platzhalter ist keine Listenoption), im Segment-Schalter `INCOME` anklicken, Betrag (ganzzahlig) eingeben.
3. `window.__krtNoReload` setzen und absenden — der Finanzeintrag tauscht den Finanzen-Bereich per AJAX aus (#574), ohne Post/Redirect/Get.
4. Detailseite erneut öffnen (`E2eSupport.navigate`, das die `Response` zurückgibt).

## Erwartetes Ergebnis

- Nach dem Absenden erscheint der Bearbeiten-Button des neuen Eintrags **in place** (`#pane-fin button.edit-finance-btn[data-amount='…']`), und der Reload-Marker ist noch gesetzt.
- Das erneute Öffnen liefert **HTTP 200** (vor dem Fix: 500), und derselbe Button mit dem gerundeten Betrag als `data-amount` ist wieder vorhanden.

## Sonderfälle & Lehren

- **Restringierter Thymeleaf-Kontext:** `th:data-*` wird in einem eingeschränkten Ausdruckskontext ausgewertet, der `@bean`-Referenzen verbietet. `@moneyFormat.round(...)` direkt in `th:data-amount` wirft daher — der gerundete Wert muss über `th:with` gebunden und nur die Variable gelesen werden. Genau dieser Loop-Körper lief in keinem früheren Render-Test (alle übergaben eine leere Finanzliste), weshalb der Bug in Produktion landete.
- **Teilnehmer ist Pflicht:** Das Finanz-Modal erzwingt eine Teilnehmer-Auswahl. Ohne geseedeten Teilnehmer bietet die Pflicht-Combobox keine Option an, und der Submit scheitert an der Validierung — daher der externe Teilnehmer als Vorbedingung.
- **Ganzzahlige Beträge:** Das Eingabefeld ist `step="1"` + `@WholeNumber`; die HALF_UP-Rundung ist hier ein No-op (fraktionale Rundung deckt der Unit-Test ab). Das `data-amount` trägt den blanken Integer (keine Tausender-Trennung), die Tabellenzelle dagegen den gruppierten Wert.
- **Status über `navigate`:** `E2eSupport.navigate` gibt die `Response` der erfolgreichen Navigation zurück, sodass der Test den 200 direkt prüft; der WebKit-Abbruch-Retry der Methode bleibt dabei erhalten.
