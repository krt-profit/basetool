# UC-48 — Schiffsliste importieren: mehrdeutigen Namen wählen

|                |                                                                                                                                    |
|----------------|------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-48                                                                                                                              |
| **Tag**        | `e2e`                                                                                                                              |
| **Testklasse** | [`HangarImportChoiceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/HangarImportChoiceE2eTest.java) |

## Akteur

Authentifiziertes Mitglied (`test-order-reader`), dessen Hangar kein anderer Hangar-Test zählt.

## Vorbedingungen

- Eingeloggte Session (UC-01).
- UEX-Katalog per JDBC geseedet (`seedCatalog`) mit dem Hersteller „E2E Manufacturer".
- Zwei Schiffstypen per `seedSql("/hangar-import-choice-seed.sql")`: „E2E Choice Hornet Mk I" und
  „E2E Choice Hornet Mk II". Ohne das Herstellerwort „E2E" passt der Name „Choice Hornet" auf beide
  (REQ-HANGAR-019).

## Auslöser

Der User lädt über „Schiffsliste importieren" einen CCU-Game-Fleetview-Export mit zwei Einträgen
„Choice Hornet" hoch.

## Hauptablauf

1. Navigiere zu `/hangar` und setze den Marker `window.__krtNoReload`.
2. Setze die Datei auf `hangar-import-file`; warte auf die Antwort des `POST /hangar/import/ships`.
3. Das Import-Ergebnis zeigt „Nicht eindeutig: 1" und unter `hangar-import-ambiguous` den Namen mit
   einer Auswahl aus „Nicht importieren" und den beiden Schiffen; `hangar-import-apply` ist
   deaktiviert.
4. Wähle „E2E Choice Hornet Mk II"; der Knopf wird aktiv. Klicke ihn und warte auf den zweiten
   `POST /hangar/import/ships`.
5. Schließe das Ergebnis über `#import-result-close`.

## Erwartetes Ergebnis

- Der zweite Lauf meldet „Importiert: 2" und „Nicht eindeutig: 0"; Auswahlbereich und Knopf sind
  ausgeblendet.
- Nach dem Schließen stehen zwei `hangar-ship-row` mit „E2E Choice Hornet Mk II" in der Liste, ohne
  dass die Seite neu geladen hat (REQ-FE-001).

## Sonderfälle & Lehren

- **Der zweite Lauf schickt dieselbe Datei** mit dem JSON-Teil `choices`. Der Import ordnet vor dem
  Anlegen zu, deshalb legt er nur die gewählten Schiffe an.
- Der Test braucht den ephemeren Stack (`assumeTrue(STACK.managesStack())`), weil er den Katalog per
  JDBC ergänzt.
