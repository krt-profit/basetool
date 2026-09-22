# UC-07 — Kernseiten-Smoke (nicht-destruktiv)

|                |                                                                                                                              |
|----------------|------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-07                                                                                                                        |
| **Tag**        | `smoke`                                                                                                                      |
| **Testklasse** | [`CorePagesSmokeE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/CorePagesSmokeE2eTest.java) |

## Akteur

Authentifizierter User. Gegen Staging der dort konfigurierte Test-User (Credentials aus CI-Secrets), ephemer der `test-admin`.

## Vorbedingungen

- Eingeloggte Session (einmaliger Login im `@BeforeAll`).
- **Kein** Daten-Seeding — der Flow ist rein lesend.

## Auslöser

Nach dem Login wird jede Kernseite einzeln aufgerufen (parametrisierter Test über die Pfadliste `FrontendPageRoutes#CORE_SMOKE` aus dem Modul `test-support`).

## Hauptablauf

Für jeden Pfad in `/`, `/missions`, `/orders`, `/refinery-orders`, `/hangar`, `/operations`, `/materials`, `/materials/overview`, `/materials/profit-calculation`, `/ship-data`, `/blueprint-overview`, `/org-chart`, `/notifications`, `/personal-inventory`, `/personal-inventory/blueprints`:

1. Navigiere mit der authentifizierten Session (einmal im `@BeforeAll` als `storageState` gesichert) zu `<baseUrl><pfad>`.
2. Prüfe, dass die authentifizierte App-Shell rendert: das Abmelde-Element `nav-logout` der Seitenleiste ist sichtbar.
3. Schlägt die Prüfung fehl, sichert `E2eSupport.dump` Screenshot und DOM unter `smoke-<pfad>`.

## Erwartetes Ergebnis

Jede Kernseite rendert für einen eingeloggten User die App-Shell — keine Weiterleitung zum Identity Provider, kein Fehler.

## Sonderfälle & Lehren

- **Nicht-destruktiv & ziel-agnostisch:** Der Flow erzeugt/ändert nichts und ist daher gefahrlos gegen ein geteiltes Staging-Deployment einsetzbar. Er läuft sowohl gegen den ephemeren Stack als auch (mit gesetztem `E2E_BASE_URL`) gegen Staging.
- **Assertion-Ziel `nav-logout`:** Es löste `nav-orders` ab. Die Navigationslinks liegen inzwischen in eingeklappten `<details>`-Abschnitten (`display:none`, bis sie aufgeklappt werden); das Abmelde-Element dagegen rendert für jeden angemeldeten Nutzer immer. Seine Sichtbarkeit beweist, dass die Seite weder einen Fehler zeigt noch zum Identity Provider umgeleitet hat.
- **Die Pfadliste ist ein Ausschnitt aus einem Katalog.** Bis 2026-09-13 zählten dieser Test, `AdminPagesSmokeE2eTest` und `TouchClassLayoutE2eTest` die Seitenrouten je von Hand auf, und die drei Listen widersprachen sich. Heute stammen alle aus `FrontendPageRoutes`; `PageRouteCatalogueTest` prüft, dass jeder Eintrag eine existierende Seitenroute ist. Welche Seiten in *diesen* Ausschnitt gehören, bleibt eine Auswahl: Sie müssen auch für ein Nicht-Admin-Konto rendern, weil das Ziel ein geteiltes Staging sein kann.
- **Nur `@Tag("smoke")`:** Die Klasse läuft über `./gradlew :frontend:smokeTest`, nicht über `e2eTest`. Der Smoke-Subset läuft in einem eigenen Workflow (`e2e-smoke.yml`) nächtlich um 04:00 UTC und per `workflow_dispatch` gegen Staging (nur wenn die Repository-Variable `E2E_BASE_URL` gesetzt ist; Nutzer aus den Secrets `E2E_USERNAME`/`E2E_PASSWORD`) — nicht als PR-Check. Lokal/ephemer braucht WebKit den Hosts-Eintrag `127.0.0.1 host.docker.internal` (siehe den Cross-Browser-Abschnitt im [Projekt-README](../../README.md)).
