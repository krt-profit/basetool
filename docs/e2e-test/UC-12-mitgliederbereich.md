# UC-12 — Mitgliederbereich: ohne Anmeldung kommt niemand hinein

|                |                                                                                                                                                                                                                                                         |
|----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-12                                                                                                                                                                                                                                                   |
| **Tag**        | `e2e`                                                                                                                                                                                                                                                   |
| **Testklasse** | [`AnonymousSurfaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AnonymousSurfaceE2eTest.java) · [`NoRoleGateE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NoRoleGateE2eTest.java) |

> **Ersetzt „UC-12 — Anonymer Auftrag anlegen" (bis 2026-09-06).**
> Der alte Use Case beschrieb das öffentliche Anfrageformular: ein nicht angemeldeter Gast wählte
> Auftraggeber und bearbeitende Einheit, und ein Auftrag ohne Autor lief auf ein konfiguriertes
> Eingangs-Spezialkommando. Das Formular ist mit [ADR-0149](../adr/0149-the-job-order-request-form-requires-a-login.md)
> entfallen, die Einstellung mit `V234`, und mit [ADR-0159](../adr/0159-the-basetool-has-no-anonymous-or-guest-surface.md)
> die anonyme Oberfläche insgesamt. Was an dieser Stelle zu prüfen bleibt, ist das Gegenteil des
> alten Ablaufs — und genau deshalb steht es hier: eine Funktion, die nur *entlinkt* wurde, kommt
> beim nächsten Routen-Eintrag zurück.

## Akteur

Ein Besucher ohne Sitzung, und — im zweiten Teil — `test-norole`: ein freigeschaltetes Konto ohne
jede Anwendungsrolle.

## Vorbedingungen

- Keine Sitzung (jeweils ein frischer Browser-Kontext).
- Für den Deep-Link-Teil: `test-member` (der Seeder legt ihn an) und eine einmalige Anmeldung
  vorab, damit der Nutzungsbedingungen-Gate die gemerkte Ziel-URL nicht verbraucht.
- Für den Rollen-Teil: der Realm-Fixture-Nutzer `test-norole` (in `default-roles-iri`, ohne
  Anwendungsrolle).

## Auslöser

Der Besucher öffnet eine beliebige Seite des Tools.

## Hauptablauf

### Die öffentliche Oberfläche ist eine Liste

1. `/missions`, `/operations`, `/orders`, `/orders/create`, `/inventory/all` und `/hangar`
   anonym aufrufen. Jede Seite führt in den OAuth2-Ablauf oder auf die Startseite; keine Zeile
   und kein Formular der Seite rendert.
2. `/` aufrufen: die Startseite zeigt beide Anmelde-Einstiege (`landing-login`,
   `landing-login-discord`) und **keine** Tabelle — die Sieben-Tage-Einsatzübersicht stand hier.
3. `/terms`, `/privacy`, `/impressum` aufrufen: sie rendern ohne Anmeldung.

### Hintergrundaufrufe werden abgewiesen, nicht umgeleitet

4. `GET /catalog/material-search?q=x` mit `Accept: application/json` über einen
   `APIRequestContext` (kein Browser-Navigationskontext) → `401` mit `X-Reauthenticate`.

### Der Deep-Link überlebt die Anmeldung

5. `/hangar` anonym aufrufen, anschließend anmelden → die Seite nach der Anmeldung ist `/hangar`.
6. Dasselbe für `/missions` — eine Seite, die vorher öffentlich war.

### Ein Konto ohne Rolle

7. Als `test-norole` anmelden → die Hinweisseite `#pending-approval-no-role` erscheint, und der
   Wartetext `#pending-approval-waiting` gerade **nicht**.
8. Als `test-norole` `/inventory/all` aufrufen → keine Lager-Tabelle, wieder die Hinweisseite.

## Erwartetes Ergebnis

Ohne Anmeldung erreicht ein Besucher genau die Startseite, die Rechtsseiten und die Assets. Mit
Anmeldung, aber ohne Rolle, erreicht ein Konto genau die Hinweisseite. Ein Deep-Link führt nach der
Anmeldung dorthin, wohin er zeigte.

## Sonderfälle & Lehren

- **Navigation und Hintergrundaufruf sind zwei verschiedene Antworten** (`REQ-SEC-012`). Dieselbe
  URL antwortet einer Browser-Navigation mit `302` in den Login und einem `fetch` mit `401` plus
  `X-Reauthenticate` — sonst ersetzt ein halb ausgefülltes Formular sich selbst durch eine
  Anmeldemaske. Eine Prüfung, die nur eine der beiden Formen kennt, prüft die falsche Hälfte.
- **Die erschöpfende Hälfte liegt woanders.** `AnonymousSurfaceSweepTest` und
  `AnonymousSurfaceSweepMvcTest` gehen *jedes* Mapping des Dispatchers durch; dieser Use Case prüft,
  was MockMvc nicht kann — den echten OAuth2-Umweg, die Silent-SSO-Probe, den Cookie-Jar und den
  Deep-Link.
- **Der Nutzungsbedingungen-Gate verbraucht die gemerkte Ziel-URL.** Bei der allerersten Anmeldung
  eines Kontos steht er zwischen Login und Ziel. Der Test meldet das Konto deshalb einmal vorab an,
  sonst hinge das Ergebnis daran, welcher andere Test denselben Nutzer zuerst angemeldet hat.
- **Der Zwischenspeicher merkt sich nur echte Seitenaufrufe** (WP-F 11). Sonst legte jeder
  Hintergrundaufruf eines abgemeldeten Tabs eine Sitzung in Redis an, nur um eine URL zu halten, zu
  der niemand je zurückgeführt wird.
- **„Freigabe ausstehend" ist die falsche Auskunft für ein Konto ohne Rolle.** Die Freigabe liegt
  vor; es fehlt die Rolle. Beide Blöcke liegen in derselben Vorlage hinter einem Schalter, deshalb
  prüft der Test beide Richtungen.
- **Staging.** Der Deep-Link-Teil läuft nur im ephemeren Modus (`assumeTrue(managesStack)`), weil er
  den geseedeten `test-member` braucht.

