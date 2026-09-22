# UC-01 — Login via Keycloak

|                |                                                                                                                      |
|----------------|----------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-01                                                                                                                |
| **Tag**        | `e2e`                                                                                                                |
| **Testklasse** | [`LoginSmokeE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/LoginSmokeE2eTest.java) |

## Akteur

Unauthentifizierter Besucher, der sich als synthetischer Test-User `test-admin` anmeldet.

## Vorbedingungen

- Der Stack läuft (ephemerer Stack oder Staging).
- Der Keycloak-Realm `iri` ist importiert und enthält den User `test-admin` / `test-admin-pw` (synthetischer Wegwerf-Realm `realm-export.e2e.json`).

## Auslöser

Der Browser ruft den Spring-OAuth2-Login-Einstieg `/oauth2/authorization/keycloak` auf.

## Hauptablauf

1. Navigiere zu `<baseUrl>/oauth2/authorization/keycloak` → Redirect auf die Keycloak-Default-Loginseite.
2. Fülle `#username` und `#password`, klicke `#kc-login`.
3. Keycloak authentifiziert und leitet zurück auf die Frontend-Origin (`<baseUrl>/...`).

## Erwartetes Ergebnis

- Der Browser landet wieder auf der Frontend-Origin (Authorization-Code-Flow abgeschlossen).
- Eine Redis-backed Spring-Session ist etabliert: der Browser-Kontext hält ein Cookie namens `SESSION` — die Session ist **cookie-**, nicht token-getragen.
- Der authentifizierte Zustand wird als Playwright-`storageState` nach `build/e2e/storageState.json` geschrieben (für Diagnose und manuelle Wiederverwendung; die übrigen Testklassen melden sich selbst an, siehe unten).

## Sonderfälle & Lehren

- Die Browser-Session trägt **kein Bearer-Token**; Token-Injection brächte nichts — es muss einmal echt durch die UI eingeloggt werden.
- Der Flow zeichnet einen Playwright-Trace nach `build/e2e/trace.zip` auf (`npx playwright show-trace build/e2e/trace.zip`).
- `storageState` wird **nicht** über Testklassen hinweg memoisiert — jede Klasse loggt frisch ein (Isolation; Cross-Class-Session-Sharing verursachte sonst nicht-deterministische Fehlschläge).
