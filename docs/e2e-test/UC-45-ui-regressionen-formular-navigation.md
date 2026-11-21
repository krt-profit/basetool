# UC-45 — UI-Regressionen: Zurück-Cache, Formulare, Picker, Zahlenfelder & Datum/Zeit-Layout

|                |        |
|----------------|--------|
| **ID**         | UC-45  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`BfcacheRefreshE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/BfcacheRefreshE2eTest.java) · [`ComboboxBlurRestoresValueE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ComboboxBlurRestoresValueE2eTest.java) · [`MissionDatetimeSplitLayoutE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionDatetimeSplitLayoutE2eTest.java) · [`UnsavedChangesGuardE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/UnsavedChangesGuardE2eTest.java) · [`PickerSessionLossE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/PickerSessionLossE2eTest.java) · [`MissionUserAutocompleteOverflowE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionUserAutocompleteOverflowE2eTest.java) · [`NumberFieldLocaleE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NumberFieldLocaleE2eTest.java) |
| **Spec**       | [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (REQ-FE-008, REQ-FE-016, REQ-FE-017, REQ-FE-024, REQ-FE-031) · [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-009, REQ-UI-013, REQ-UI-037, REQ-UI-038) · [ADR-0013](../adr/0013-frontend-bfcache-history-restore-reload.md) |

## Akteur

`test-admin`; die Zahlenfelder prüft `test-officer`.

## Vorbedingungen

- **Zurück-Cache:** keine; getestet wird auf der Startseite `/`, die `common-handlers.js` wie jede Seite über das gemeinsame Head-Fragment lädt.
- **Combobox:** ein frisch geseedetes Material, damit der Picker mindestens eine Option hat.
- **Datum/Zeit-Layout:** ein Einsatz (per REST, IRIDIUM-Mitgliedschaft vorher) und der handelnde Nutzer als registrierter Teilnehmer, damit das Crew-Board einen Bearbeiten-Button (`.edit-participant-btn`) rendert.
- **Nutzer-Autocomplete:** ein Einsatz; die Relay-Antwort `/users/search` liefert eine Playwright-Route mit synthetischer Liste.
- **Änderungswächter, Picker-Sitzung:** kein Seed über die IRIDIUM-Mitgliedschaft hinaus.
- **Zahlenfelder:** `test-officer` als Staffelleiter mit einem Einheiten-Bankkonto (`assignStaffelleiterSeat`, `ensureOrgUnitBankAccount`); nichts wird abgesendet.

## Auslöser

Der Browser stellt eine Seite aus dem Zurück-Cache wieder her; der Nutzer verlässt eine Combobox mit halb getipptem Text; Datum- und Zeitfelder rendern in schmalen Spalten; der Nutzer filtert und verlässt die Seite, sucht in einem Picker nach Sitzungsende oder tippt einen Betrag.

## Hauptablauf

### Zurück-Cache erzwingt frisches Rendern (`BfcacheRefreshE2eTest`, REQ-FE-008, ADR-0013)

1. `/` laden, einen Marker auf das lebende Dokument stempeln, dann `new PageTransitionEvent('pageshow', {persisted: true})` auslösen.

### Combobox stellt Wert **und** Beschriftung wieder her (`ComboboxBlurRestoresValueE2eTest`, REQ-FE-017)

2. Auf `/inventory/input?source=my` im Material-Picker einen Eintrag festlegen.
3. Den Text durch einen nicht passenden überschreiben.
4. Den Fokus wegnehmen.

### Datum/Zeit bleiben in ihrer Spalte (`MissionDatetimeSplitLayoutE2eTest`, REQ-UI-013)

5. Auf der Einsatz-Detailseite das Bearbeiten-Modal eines Teilnehmers öffnen und die beiden Zeitfelder („Startzeit", „Endzeit") vermessen.
6. Im Tab Verwaltung jedes Zeitfeld des Einsatzformulars vermessen (Treffen Teamspeak, geplanter und tatsächlicher Start und Ende).
7. Beide Messungen bei 1280, 1440, 1600 und 1800 px Breite wiederholen.

### Änderungswächter nur für Datenformulare (`UnsavedChangesGuardE2eTest`, REQ-FE-024)

8. Auf `/admin/terms` den Zustimmungsfilter umschalten und die Seite über einen Link verlassen; dasselbe nach einer Hangar-Suche.
9. Auf `/profile` die Beschreibung ändern, ohne zu speichern, und einen Link anklicken.

### Picker nach Sitzungsverlust (`PickerSessionLossE2eTest`, REQ-FE-031)

10. Auf `/inventory/input?source=admin` alle Cookies des Kontexts löschen (auch die von Keycloak) und im Eigentümer-Picker suchen.

### Obergrenze der Nutzer-Autocompletes (`MissionUserAutocompleteOverflowE2eTest`, REQ-FE-016)

11. Auf der Einsatzseite in „Teilnehmer hinzufügen" und im Party-Lead-Feld suchen, während die Relay-Antwort 50 bzw. 51 Treffer liefert.

### Zahlenfelder in der Seitensprache (`NumberFieldLocaleE2eTest`, REQ-UI-037, REQ-UI-038)

12. Auf `/org-unit-bank` mit `?lang=en` und `?lang=de` dieselben Ziffern in das Betragsfeld der Einzahlung tippen.

## Erwartetes Ergebnis

- **Zurück-Cache:** Der globale `pageshow`-Handler reagiert mit `location.reload()`; das frische Dokument hat den Marker nicht mehr (per `waitForFunction`, das die Navigation überlebt, mit 60 s Timeout).
- **Combobox:** Nach dem Überschreiben ist der abgeschickte Wert **leer** (Zwischenzustand, ausdrücklich geprüft); nach dem Fokusverlust sind Wert und Beschriftung **gemeinsam** wiederhergestellt, und das Feld ist gültig.
- **Layout:** Datum- und Zeitteil jeder `.datetime-split-group` liegen innerhalb der Spalte ihrer `.form-row` — im Modal und im Formular, bei jeder Breite.
- **Änderungswächter:** Filter und Suche schalten ihn nie scharf, die Seite wechselt ohne Rückfrage; die ungespeicherte Beschreibung hält den Link weiter auf.
- **Picker-Sitzung:** Der Picker schickt den Nutzer zur Anmeldung, statt still „keine Treffer" zu zeigen (REQ-SEC-012).
- **Autocomplete:** Beide Listen zeigen höchstens 50 Zeilen; erst der 51. Treffer blendet den Hinweis „weiter tippen" ein.
- **Zahlenfelder:** Englisch gruppiert beim Tippen mit Kommas (1,999,999), Deutsch mit Punkten (1.999.999); der Wert des Felds bleibt kanonisch, und die Gebührenzeile der Einzahlung folgt derselben Sprache (REQ-BANK-065).

## Sonderfälle & Lehren

- **Ein synthetisches Ereignis statt echtem Zurück-Cache.** Ob eine Engine eine Seite im Zurück-Cache hält, hängt von Timing und Heuristik ab und ist unter Playwright nicht in allen drei Engines reproduzierbar. Das Signal, auf das der Handler hört (`PageTransitionEvent.persisted`), lässt sich im Konstruktor setzen — so läuft auf Chromium, Firefox und WebKit genau der Produktionspfad. Ohne Handler bliebe der Marker stehen, und das Warten liefe in den Timeout.
- **Die Combobox verlor die Hälfte der Auswahl.** Der Enhancer teilt ein natives `<select>` in ein Hidden-Input (Name und abgeschickter Wert) und ein sichtbares Textfeld (`required`). Blur und Esc setzten nur die Beschriftung zurück und löschten die Gültigkeitsmeldung — das Formular schickte einen **leeren** Wert ab, obwohl das Feld ausgefüllt aussah. Aufgefallen ist es einem Bankmitarbeiter bei einer Auszahlung über Limit: der zusätzliche Klick auf „Freigabe eingeholt" war der Blur, `holderId` fehlte im JSON, und das Backend lehnte mit `@NotNull` ab. Der Test läuft auf dem Material-Picker, weil der Fehler im gemeinsamen Enhancer lag. Der geprüfte Zwischenzustand verhindert, dass eine Regression im Leeren beim Tippen den Test grün lässt.
- **Die Spalte war zu schmal für ihren Inhalt.** Datum und Zeit sind fest breit (10,5 rem + `--space-2` + 7 rem = 18 rem), eine mehrspaltige `.form-row` gibt aber je Spalte nur 250 px Untergrenze. Im 600 px breiten Modal ragte die Zeit rund 13 px heraus, im dreispaltigen Verwaltungsformular rund 14,7 px je Gruppe. Der Fix erklärt die echte Untergrenze, sodass die Zeile umbricht.
- **Mehrere Breiten, weil der Fehler ein Band ist:** Das Modal läuft bei jeder Desktop-Breite über, das Verwaltungsformular nur, wenn die Zeile zwei Gruppen nebeneinander hält, aber keiner 18 rem gibt. Die vier Breiten der Geräteklassen-Leiter (REQ-UI-009) halten den Wächter ehrlich, ohne ein Band fest zu verdrahten.
- **Rechtecke, nicht `scrollWidth`.** Der Überlauf landet im rechten Padding des Containers; `scrollWidth - clientWidth` maß am kaputten Layout 0.
