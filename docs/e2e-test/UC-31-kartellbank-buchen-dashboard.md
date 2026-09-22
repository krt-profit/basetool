# UC-31 — Kartellbank: Buchen, Dashboard & Admin-Reset

|                |                                                                                                                                                                                                                                                                                                                                                    |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-31                                                                                                                                                                                                                                                                                                                                              |
| **Tag**        | `e2e`                                                                                                                                                                                                                                                                                                                                              |
| **Testklasse** | [`BankBookingE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/BankBookingE2eTest.java) · [`BankDashboardE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/BankDashboardE2eTest.java) · [`BankAdminResetE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/BankAdminResetE2eTest.java) |
| **Spec**       | [`bank.md`](../specs/bank.md) (REQ-BANK-004/-006/-011/-013/-014/-015/-016/-033/-044)                                                                                                                                                                                                                                                                |

## Akteure

- `test-bank-management` — Bankleitung; legt Konten, Halter und Freigaben an und bucht per API.
- `test-bank-employee` — Bankmitarbeiter mit einer Freigabe (lesen, buchen) auf einem Konto; treibt die Buchungsmaske.
- `test-admin` — für den globalen Wipe-Reset.

Alle drei tragen neben ihrer Bankrolle die Basisrolle `KRT Member` (REQ-SEC-053).

## Vorbedingungen

Nur im ephemeren Modus per REST geseedet: Sonderkonten (`createBankAccount`, Typ `SPECIAL`), Halter für Mitarbeiter und Leitung (`registerBankHolder`), Kontofreigaben (`createBankGrant`) und Startbuchungen (`bankDeposit`). Buchungstests, die absolute Salden prüfen, legen je ein eigenes frisches Konto an — JUnit führt Methoden nicht in Quellreihenfolge aus, und der geteilte Stack behält alle Postings.

## Auslöser

Geld wird eingezahlt, ausgezahlt oder umgebucht; die Leitung öffnet das Dashboard; ein Admin setzt die Bank zurück.

## Hauptablauf

### Buchungen (`BankBookingE2eTest`)

1. **Einzahlung + Teilauszahlung** per API: 1000 ein, 400 aus.
2. **Überziehung des Halters**: eine Auszahlung über den Bestand des Halters hinaus.
3. **Umbuchung** zwischen zwei Konten **und** zwei Haltern über 200 aUEC (`POST /api/v1/bank/transfers`).
4. **Selbst-Umbuchung** auf dasselbe Konto und denselben Halter.
5. **Bruchbetrag** bei einer Einzahlung.
6. **Buchungsmaske (UI)**: der Mitarbeiter öffnet `/bank/accounts/{id}`, setzt `window.__krtNoReload` und bucht über das K1-Modal zwei Einzahlungen direkt hintereinander.
7. **Gegenpartei-Picker (UI)**: in der Maske einen registrierten Nutzer über die serverseitig suchende Combobox (`/users/search-bank`) wählen, dann auf „kein Tool-Account" umschalten. Der Test schickt nichts ab.

### Dashboard und Exporte (`BankDashboardE2eTest`)

8. Die Leitung öffnet `/bank`.
9. Der berechtigte Mitarbeiter ruft den Kontoauszug als PDF für die letzten sieben Tage ab (`GET /api/v1/bank/accounts/{id}/statement?from=…&to=…`).
10. Mitarbeiter und Leitung rufen den Drei-Monats-Bericht ab (`/api/v1/bank/export/three-month-report`).

### Wipe-Reset (`BankAdminResetE2eTest`)

11. `test-admin` öffnet `/admin/bank`, tippt im Gefahren-Modal das Bestätigungswort `WIPE` (`bank-wipe-confirm`) und sendet ab (`bank-wipe-submit`).

## Erwartetes Ergebnis

- Die berechneten Salden (compute-on-read) bewegen sich wie gebucht; Überziehung und Selbst-Umbuchung werden mit dem stabilen **409** abgelehnt, der Bruchbetrag mit **400** — der Saldo bleibt jeweils unverändert.
- Die Umbuchung belastet die Quelle mit Betrag **plus** Spielgebühr (0,5 % → 201) und schreibt dem Ziel den vollen Betrag gut (200) (REQ-BANK-033, ADR-0052).
- Die Buchungsmaske tauscht den Kontobereich in place (`fragment=accountBody`): der Reload-Marker überlebt beide Buchungen, der angezeigte Saldo ändert sich jedes Mal, und die zweite Einzahlung wird angenommen.
- Der Gegenpartei-Picker aktiviert beim registrierten Nutzer das abhängige Einheiten-Select und wählt die einzige Mitgliedschaft vor; der Umschalter tauscht die Combobox gegen das Freitextfeld und lässt das (nun auf alle Einheiten erweiterte) Select benutzbar.
- Das Dashboard zeigt die Summenleiste (`bank-totals`) und Kontokarten mit mindestens einer Sparkline. Der PDF-Export liefert dem berechtigten Mitarbeiter **200**; der Drei-Monats-Bericht liefert dem Mitarbeiter **403** und der Leitung **200**.
- Nach dem Wipe ist der geseedete Saldo **0**, ein Toast erscheint ohne Reload, und die Bank-Auditspur hat mindestens einen Eintrag mehr.

## Sonderfälle & Lehren

- **Geldbuchungen tragen keine Client-`@Version`.** Deshalb kann eine unmittelbar folgende zweite Buchung strukturell keinen Stale-Version-409 bekommen — genau das beweist der Doppel-Einzahlungs-Fall (#579, REQ-FE-001).
- **Die Salden werden nicht gespeichert, sondern berechnet.** Jede Prüfung liest das Konto-Detail-JSON zurück, statt der Anzeige zu vertrauen.
- **Der Wipe ist global.** Er verträgt sich mit den übrigen Klassen nur, weil die E2E-Klassen nacheinander laufen.
- **Die Sichtbarkeitsregeln** (wer welches Konto sieht, wer bucht, wer die Auditspur liest) stehen in [UC-32](UC-32-kartellbank-antraege-berechtigungen.md).
