# UC-02 — Einsatz anlegen

|                |                                                                                                                                                                                                       |
|----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-02                                                                                                                                                                                                 |
| **Tag**        | `e2e`                                                                                                                                                                                                 |
| **Testklasse** | [`MissionCreateE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionCreateE2eTest.java) (Code-/Englisch-Begriff *Mission* = dt. **Einsatz**, Endpunkt `/missions`) |

## Akteur

Authentifizierter User mit Mitgliedschaft in der IRIDIUM-Staffel.

## Vorbedingungen

- Eingeloggte Session (UC-01, wiederverwendeter `storageState`).
- IRIDIUM-Mitgliedschaft für den Test-User geseedet (`BackendSeeder.ensureIridiumMembership`) — Einsätze sind staffel-scoped und 400en sonst.

## Auslöser

Der User öffnet das Einsatz-Anlegen-Formular.

## Hauptablauf

1. Navigiere zu `/missions` und folge dem `missions-create-link` nach `/missions/new`.
2. Warte auf den vollständigen Page-Load (`waitForURL` + `waitForLoadState`), **bevor** Felder gefüllt werden.
3. Fülle `mission-name-input`, `mission-start-date` (Zukunftsdatum, z. B. heute + 7 Tage) und `mission-start-time` (12:00).
4. Submit über `button[type='submit'][form='mission-form']`.

## Erwartetes Ergebnis

Der Einsatz erscheint in der Liste unter `/missions` als `mission-row` (Filter auf den Einsatznamen).

## Sonderfälle & Lehren

- **Clientseitige `required`-Blockade:** Planned-Start-Datum und -Zeit sind HTML-`required`. Fehlen sie, blockiert der Browser den Submit **stumm** (keine Server-Fehlermeldung) — der Test bleibt auf dem Formular. Daher beide Felder füllen.
- **Zukunftsdatum nötig:** sonst greift die „nicht in der Vergangenheit"-Prüfung.
- **datetime-splitter-Race:** `datetime-splitter.js` leert die Datum/Zeit-Picker beim `DOMContentLoaded` aus dem leeren Hidden-Feld. Wer vor diesem Init füllt, dessen Werte werden wieder gelöscht → stummer Submit-Block. Erst auf den vollständigen Load warten, dann füllen.
