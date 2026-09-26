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
2. `E2eSupport.submitKeycloakLogin`: warte auf den `load`-Zustand der Loginseite und ein sichtbares `#kc-login`, fülle `#username` und `#password`, klicke `#kc-login` und warte, bis der POST an `login-actions/authenticate` den Browser verlassen hat. Geht nach 10 s kein POST raus, wird genau einmal erneut geklickt.
3. Keycloak authentifiziert und leitet zurück auf die Frontend-Origin (`<baseUrl>/...`) — binnen 30 s, gemessen ab dem POST.

## Erwartetes Ergebnis

- Der Browser landet wieder auf der Frontend-Origin (Authorization-Code-Flow abgeschlossen).
- Eine Redis-backed Spring-Session ist etabliert: der Browser-Kontext hält ein Cookie namens `__Host-SESSION` (bis 2026-09-22: `SESSION`) — die Session ist **cookie-**, nicht token-getragen.
- Der authentifizierte Zustand wird als Playwright-`storageState` nach `build/e2e/storageState.json` geschrieben (für Diagnose und manuelle Wiederverwendung; die übrigen Testklassen melden sich selbst an, siehe unten).

## Sonderfälle & Lehren

- Die Browser-Session trägt **kein Bearer-Token**; Token-Injection brächte nichts — es muss einmal echt durch die UI eingeloggt werden.
- Der Flow zeichnet einen Playwright-Trace nach `build/e2e/trace.zip` auf (`npx playwright show-trace build/e2e/trace.zip`).
- Ein Klick auf `#kc-login` kann **verschluckt** werden: Am 2026-09-26 (Run 36259175370, Firefox 1024×768) meldete Playwright den Klick als erledigt, aber der `onsubmit`-Handler lief nie (der Button blieb aktiv) und es ging kein POST raus; die Seite stand 30 s ausgefüllt da. Deshalb wartet der Helfer auf den POST statt auf den Klick, und Firefox startet ohne die kontextuelle Warnung vor Passwortfeldern auf `http://`-Seiten (`security.insecure_field_warning.contextual.enabled`) — Keycloak läuft im Stack auf `http://host.docker.internal:18080`. `E2eKeycloakSubmitSeamTest` (Frontend-Unit-Tests) verbietet `#kc-login` außerhalb von `E2eSupport`.
- Die ausgegebene Zeile nennt die Gesamtdauer, die Dauer vom POST bis zur Landung und die Zahl der Klicks; eine `2` dort ist ein geschluckter Klick, der sonst unsichtbar bliebe.
- `storageState` wird **nicht** über Testklassen hinweg memoisiert — jede Klasse loggt frisch ein (Isolation; Cross-Class-Session-Sharing verursachte sonst nicht-deterministische Fehlschläge).
