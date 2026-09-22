# UC-32 — Kartellbank: Einheiten-Anträge, Berechtigungen & Sichtbarkeit

|                |                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
|----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-32                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| **Tag**        | `e2e`                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| **Testklasse** | [`BankOrgUnitRequestsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/BankOrgUnitRequestsE2eTest.java) · [`BankPermissionsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/BankPermissionsE2eTest.java) · [`BankRequestsLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/BankRequestsLiveSyncE2eTest.java) · [`OrgUnitBankVisibilityMatrixE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrgUnitBankVisibilityMatrixE2eTest.java) |
| **Spec**       | [`bank.md`](../specs/bank.md) (REQ-BANK-008/-009/-010, -021/-022/-023, -034…-038, -055/-056) · [ADR-0043](../adr/0043-bank-account-responsibility-and-visibility.md)                                                                                                                                                                                                                                                                                                      |

## Akteure

- `test-bank-management`, `test-bank-employee`, `test-bank-member` — Bankrollen (jeweils plus `KRT Member`).
- `test-officer` — Officer, der eine Staffel beaufsichtigt; stellt Anträge für deren Einheitenkonto.
- `test-bereich` — per Mitgliedschaft Bereichsleiter (nicht per Keycloak-Rolle).
- `test-member`, `test-none` — einfaches Mitglied ohne Bankrolle bzw. Konto ohne Mitgliedschaft.
- `test-admin` — Admin-Override für Freigaben und Konfiguration.

## Vorbedingungen

Nur im ephemeren Modus per REST geseedet: eine Staffel mit ihrem `ORG_UNIT`-Bankkonto, Sonderkonten (`SPECIAL`), Halter, Freigaben und Mitgliedschaften. Mutierende Szenarien legen eigene Konten an, damit Freigaben nicht in andere Prüfungen durchsickern. Jeder Antrags-Test nutzt einen eigenen Betrag, um genau den eigenen Antrag im geteilten Stack wiederzufinden.

## Auslöser

Ein Officer beantragt eine Buchung für seine Einheit; Bankmitarbeiter entscheiden sie; Nutzer mit unterschiedlichen Rollen und Mitgliedschaften rufen Konten ab.

## Hauptablauf

### Anträge der Einheit (`BankOrgUnitRequestsE2eTest`, epic #666)

1. **F1 — Saldo sehen:** Der Officer öffnet `/org-unit-bank` und sieht die Saldo-Karte seiner Einheit; ein einfaches Mitglied erreicht dieselbe Seite samt Navigationseintrag, aber ohne Saldo-Karte (REQ-BANK-038).
2. **F2 — Antrag bestätigen:** Der Officer stellt über das Modal einen Einzahlungsantrag (`PENDING`, es bewegt sich noch kein Geld); ein berechtigter Mitarbeiter bestätigt ihn auf `/bank/requests` und erfasst dabei einen Halter.
3. **F2 — zurückziehen / ablehnen:** Der Officer zieht einen eigenen offenen Antrag zurück; ein Mitarbeiter lehnt einen anderen mit Begründung ab.
4. **F2 — bearbeiten (REQ-BANK-055/-056):** Der Officer stellt einen Auszahlungsantrag (Empfänger-Picker mit ihm selbst vorbelegt), klappt die Zeile auf, liest seine Angaben zurück und korrigiert den Betrag über das Bearbeiten-Modal der Zeile.
5. **Rollengrenzen:** Der Officer ruft die Mitarbeiter-Warteschlange `/bank/requests` auf, der Mitarbeiter ohne Officer-/Lead-Rolle die Seite `/org-unit-bank`.

### Berechtigungsmatrix (`BankPermissionsE2eTest`)

6. Leitung und Mitarbeiter listen die Konten (`/api/v1/bank/accounts?size=500`); ein Mitarbeiter, der zugleich Staffelmitglied ist, nutzt die Bank normal; ein einfaches Mitglied ruft das Dashboard ab — per API und über die echte `/bank`-UI.
7. Auditspur (`/api/v1/bank/admin/audit`) und Freigabe-Matrix (`/api/v1/bank/grants`) werden von Leitung bzw. Mitarbeiter abgerufen; die Leitung lädt `/bank` in der UI; das Konto-Detail-JSON des freigegebenen Kontos wird gelesen.

### Live-Sync der Warteschlange (`BankRequestsLiveSyncE2eTest`)

8. Zwei Browser-Kontexte desselben Mitarbeiters öffnen `/bank/requests`; ein offener Antrag ist per Backend geseedet. Beide warten, bis `window.krtLiveSync.subscribedTopics()` nicht leer ist. Kontext A lehnt den Antrag ab; Kontext B ist nur Zuschauer und lädt nie neu.
9. Regression: Nach einer Entscheidung klickt der Mitarbeiter in derselben Sitzung den Entscheiden-Button des nächsten Antrags.

### Sichtbarkeitsmatrix der Konten-Verantwortung (`OrgUnitBankVisibilityMatrixE2eTest`)

10. Per API als jeweiliger Nutzer: Sonderkonto-Auto-Sicht, Drill-in-Endpunkte (`/api/v1/org-units/bank/…`), Freigaben der Arten `USER`, `GLOBAL_ROLE` und `ALL_MEMBERS` (vom Admin gesetzt), Saldo-Ziel und Sichtbarkeits-Konfiguration.
11. In der UI: der Bereichsleiter öffnet den Drill-in eines gebuchten Sonderkontos.

## Erwartetes Ergebnis

- **Anträge:** Der bestätigte Antrag steht auf `CONFIRMED`, und der Saldo des Einheitenkontos ist um den beantragten Betrag gestiegen; der zurückgezogene und der abgelehnte Antrag bewegen kein Geld; der bearbeitete Antrag trägt den korrigierten Betrag. Der Officer erreicht die Mitarbeiter-Warteschlange nicht, der reine Mitarbeiter die Officer-Seite nicht — geprüft wird die **Seite**, nicht der Navigationslink.
- **Berechtigungen:** Die Leitung sieht alle Konten, der Mitarbeiter nur das freigegebene; das einfache Mitglied ohne Bankrolle sieht nichts (Dashboard verboten, keine Dashboard-Inhalte in der UI). Die Auditspur ist ADMIN-only, die Freigabe-Matrix Leitungs-only. Die Leitung sieht Dashboard, Direktbuchung und Berichte; das Detail-JSON trägt die Capability-Flags, die die K1-Buttons nutzen.
- **Live-Sync:** Kontext B entfernt den entschiedenen Antrag **in place** aus seiner Nur-offen-Warteschlange. Das Entscheidungsmodal öffnet sich für den nächsten Antrag erneut, ohne Reload.
- **Sichtbarkeit:** Ein Bereichsleiter sieht das Sonderkonto automatisch; ein Inhaber der `OFFICER`-Rolle ohne Bereichs-/OL-Sitz sieht es **nicht**, ein einfaches Mitglied auch nicht. Ein Mitglied ohne Aufsicht, Freigabe oder Sitz wird an den Drill-in-Endpunkten abgewiesen. Eine `USER`-Freigabe macht ein Einheitenkonto genau für diesen Nutzer sichtbar; `GLOBAL_ROLE`- und `ALL_MEMBERS`-Freigaben wirken auf dem Sonderkonto. Das Saldo-Ziel darf der Admin setzen, ein Mitglied nicht; die Sichtbarkeit eines Sonderkontos konfigurieren nur OL oder Bankleitung — nicht der Bereichsleiter, der es sieht. Die Drill-in-Historie zeigt fünf Spalten und **keine** Halter-Spalte (REQ-BANK-038).

## Sonderfälle & Lehren

- **Bank-Gates fragen Bankrollen und Kontofreigaben — nie die Mitgliedschaft in einer OrgUnit.** Die Sonderkonto-Auto-Sicht hängt dagegen an der **Mitgliedschaft** (Bereich/OL), nie an der Keycloak-Rolle (REQ-BANK-037). Diese Rollen-Modell-Aussage kann nur ein E2E-Test mit echtem Token beweisen; `OrgUnitBankAccessServiceTest` kann sie nur mocken.
- **Es gibt kein Nur-Bank-Konto.** `default-roles-iri` gibt jedem Konto `KRT Member` (REQ-SEC-053). Bis 2026-09-06 modellierten die Bank-Fixtures ein Konto ohne Mitgliedsrolle, das Keycloak gar nicht erzeugen kann; seit der Korrektur erscheint der Navigationslink zur Officer-Seite legitim auch beim Mitarbeiter — die Grenze war immer die Seite.
- **UI treiben, API verifizieren.** Status und Saldo werden aus dem Backend gelesen, damit die Prüfung nie mit dem In-Place-Tausch wettläuft.
- **Live-Sync-Mechanik** (#1102, REQ-FE-015, ADR-0094): Zwei Kontexte sind zwei `/ws/sync`-Sockets. Die Ablehnung bucht nichts, sendet aber `bank/[requestQueue,grid]` in den globalen `bank`-Raum; der Beitritt zum Raum wird lokal gegen die beim Handshake erfassten Rollen geprüft.
- **Modal-Wiederöffnung** (REQ-UI-013, ADR-0093): Der Erfolgspfad schloss das Modal früher per Inline-`style.display = 'none'`, das die klassenbasierte Wiederöffnung (`krtm-modal-open`) überstimmte — der Button des nächsten Antrags tat bis zum Reload nichts.
- **`CARTEL` / `CARTEL_BANK`** (feste Singleton-Konten ohne Freigabelogik) sind auf dem geteilten Stack schwer zu seeden und bleiben der Unit-Suite überlassen.
