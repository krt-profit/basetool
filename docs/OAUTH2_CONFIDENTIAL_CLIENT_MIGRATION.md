# OAuth2 Confidential-Client-Migration (Audit-Finding M-6)

> **Doc type:** Implementation runbook for [ADR-0001](adr/0001-frontend-confidential-oauth2-client.md).
> The *decision* and its rationale live in the ADR; this document is the step-by-step *how*.
> Registered in [`docs/specs/INDEX.md`](specs/INDEX.md).

**Status:** offen — Code-Anteil noch nicht implementiert, Keycloak-Anteil noch nicht durchgeführt.
**Audit-Finding:** M-6 (Security-Audit 2026-05-20).
**Schweregrad:** Medium (Defense-in-Depth — kein akut ausnutzbarer Vektor).

## Worum es geht

Der Frontend-Spring-Boot-Server ist gegenüber Keycloak aktuell als **public OAuth2 Client** registriert:

```yaml
# frontend/src/main/resources/application.yml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: basetool-frontend
            client-authentication-method: none   # ← public Client, PKCE-only
            scope: openid, profile, email, roles
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
```

"Public" heißt: beim Token-Exchange am Token-Endpoint sendet der Frontend nur den Authorization Code + PKCE-Verifier, **kein Client-Secret**. Das ist RFC-konform (RFC 8252 + RFC 9700 BCP) und für SPAs / Mobile-Apps der Standard, weil dort ein Secret nicht sicher hinterlegt werden kann.

Der Frontend hier ist aber kein SPA — er ist ein server-side Thymeleaf-SSR-Backend, also eine **confidential** Komponente. Er kann ein Secret problemlos in einem Server-Env-Var halten. Dass wir das nicht tun, ist eine verpasste Defense-in-Depth-Gelegenheit.

## Was das Secret zusätzlich schützt

Mit PKCE allein gilt: wer den Authorization Code abgreift UND den PKCE-Verifier besitzt, kann den Code einlösen. Der Code wird per `?code=…`-Query-Param an `/login/oauth2/code/keycloak` zurückgegeben — also kurz im HTTP-Pfad eines TLS-terminierten Requests sichtbar.

Realistische Interception-Vektoren:
- **Reverse-Proxy mit TLS-Termination kompromittiert** (nginx-proxy-manager auf dem Host). Wer dort Zugriff hat, sieht den Code im Klartext.
- **Server-side Request Forgery** auf einer parallelen App, die irgendwie an die NPM-Logs / Access-Logs kommt.
- **Misconfigured Open Redirect** im Frontend, der den Authorization Response an einen Angreifer-Host umleitet.
- **Browser-seitige Malware**, die den Redirect-Param mitliest.

Mit Client-Secret zusätzlich gilt: **selbst wenn der Code abgegriffen wird, kann der Angreifer ihn nicht einlösen** — der Token-Endpoint verlangt zusätzlich das Secret, das nur der echte Frontend-Server kennt. Der Angreifer müsste auch noch das `.env` auf dem Prod-Host kompromittieren — und wenn er das geschafft hat, hat er sowieso schon mehr Probleme.

PKCE bleibt im neuen Modus parallel aktiv: das Pattern ist **PKCE + Client-Secret**, nicht eins-statt-dem-anderen. Genau das ist der Defense-in-Depth-Gewinn — ein einzelner kompromittierter Layer reicht nicht mehr.

## Warum es nicht direkt im Sicherheitsaudit-Fix-PR mitgegangen ist

Die Migration ist nicht atomar in einem Commit machbar:

1. **Code-Änderung im Repo** (application.yml, docker-compose.yml, .env.example) — kommt per PR.
2. **Keycloak-Admin-Console-Klicks** — Client von public auf confidential umstellen, Secret generieren. Geht nicht per PR.
3. **`.env` auf Prod-Host updaten** mit dem neuen Secret — Operator-Arbeit, nicht im Repo.
4. **Container-Restart** mit der neuen Config + neuem Secret.

Zwischen Schritt 2 und Schritt 4 schlagen neue Logins fehl. Das braucht ein Wartungsfenster oder eine sorgfältig sequenzierte Deploy-Choreographie. Deshalb steht das Finding als Medium auf der Followup-Liste statt im 2026-05-20-Audit-Fix.

---

## Detaillierte Arbeitsliste

### Teil A — Code-Anteil (Repo-PR)

**A.1 — `frontend/src/main/resources/application.yml`**

In der `spring.security.oauth2.client.registration.keycloak`-Sektion:

```diff
 keycloak:
   client-id: basetool-frontend
-  client-authentication-method: none
+  client-authentication-method: client_secret_basic
+  client-secret: ${KEYCLOAK_FRONTEND_CLIENT_SECRET}
   scope: openid, profile, email, roles
   authorization-grant-type: authorization_code
   redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
```

`client_secret_basic` ist der gängigere der zwei Modi und sendet das Secret per HTTP-Basic-Auth-Header zum Token-Endpoint. `client_secret_post` schickt es im Body — funktioniert auch, aber Basic ist Keycloak's Default.

**A.2 — `docker-compose.yml`**

In die `x-frontend: &frontend-template`-Definition (analog zu `KEYCLOAK_ADMIN_CLIENT_SECRET` im Backend-Template) das neue Env-Var aufnehmen:

```yaml
environment:
  # ... bisherige Vars ...
  KEYCLOAK_FRONTEND_CLIENT_SECRET: ${KEYCLOAK_FRONTEND_CLIENT_SECRET:?KEYCLOAK_FRONTEND_CLIENT_SECRET must be set in .env}
```

Die `:?…`-Syntax sorgt dafür, dass `docker compose up` mit klarer Fehlermeldung abbricht, wenn das Secret nicht in `.env` gesetzt ist — gleiches Pattern wie die existierenden Secrets.

**A.3 — `.env.example`**

Neuer Eintrag mit Rotation-Anleitung, analog zum bestehenden `KEYCLOAK_ADMIN_CLIENT_SECRET`:

```bash
# Keycloak basetool-frontend OAuth2 client secret (Spring OAuth2 client login).
# Generate / rotate in the Keycloak admin console:
#   Realm "iri" -> Clients -> basetool-frontend -> Credentials -> Regenerate Secret.
KEYCLOAK_FRONTEND_CLIENT_SECRET=CHANGE_ME
```

**A.4 — `frontend/src/main/resources/application-test.yml`**

Die `@SpringBootTest`-Tests booten den OAuth2-Client. Ohne Placeholder-Secret schlägt der Context-Start fehl, sobald `client-secret: ${KEYCLOAK_FRONTEND_CLIENT_SECRET}` ohne Fallback dasteht. Ergänzen:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: test-client
            client-secret: test-client-secret
            authorization-grant-type: authorization_code
        provider:
          keycloak:
            issuer-uri: http://keycloak.example.com/realms/test
```

(Der Test-Issuer wird ohnehin nie aufgerufen — KeycloakHealthIndicator ist im Test-Profile disabled, OAuth2-Login-Flows laufen mit Mocks. Das Secret muss nur als Property auflösbar sein.)

**A.5 — `frontend/src/main/resources/application.yml` Default-Fallback (optional)**

Damit lokale Dev-Runs ohne Docker (`./gradlew :frontend:bootRun --args='--spring.profiles.active=dev'`) nicht direkt mit `IllegalArgumentException` aussteigen, kann man einen Dev-Default geben:

```yaml
client-secret: ${KEYCLOAK_FRONTEND_CLIENT_SECRET:dev-secret-placeholder}
```

Trade-off: macht den Dev-Pfad bequemer, aber maskiert auch eine fehlende Prod-Konfiguration. Empfehlung: **keinen Fallback einbauen**, stattdessen in `application-dev.yml` den Dev-Override setzen, sodass `application-prod.yml`/`application.yml` strikt bleiben. Wer ohne Docker fährt, muss `KEYCLOAK_FRONTEND_CLIENT_SECRET` setzen — gleiche Stringenz wie schon bei `SERVER_SSL_KEY_STORE_PASSWORD`.

**A.6 — `frontend/src/main/resources/application-dev.yml` (falls A.5 ohne Default)**

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-secret: ${KEYCLOAK_FRONTEND_CLIENT_SECRET:dev-secret-placeholder}
```

Nur das `client-secret`-Property überschreiben; alles andere erbt von `application.yml`.

**A.7 — Tests prüfen / anpassen**

- `frontend/src/test/java/.../config/SecurityConfigTest.java` — falls dort hartcodierte Assertions auf `client-authentication-method: none` oder das Fehlen eines `client-secret`-Feldes existieren.
- `frontend/src/test/java/.../config/RoleHierarchyTest.java` — context-loading, sollte mit dem placeholder-Secret aus application-test.yml ohne Anpassung durchlaufen, kurz verifizieren.
- Optional: einen Pinning-Test in `SecurityHeadersTest` o.ä. ergänzen, der via `Environment.getProperty("spring.security.oauth2.client.registration.keycloak.client-authentication-method")` bestätigt, dass `client_secret_basic` aktiv ist — Regression auf den public-client-Pfad würde dann den Build brechen.

**A.8 — CHANGELOG**

Im `### Security`-Block unter `## [Unreleased]` ergänzen. Form analog zu L-4:

```markdown
- **Frontend OAuth2-Client jetzt confidential (Client-Secret + PKCE statt PKCE-only)** (Audit-Finding M-6, Migration 2026-05-XX). `client-authentication-method` von `none` auf `client_secret_basic` umgestellt, neues Env-Var `KEYCLOAK_FRONTEND_CLIENT_SECRET` in docker-compose / .env.example aufgenommen. Defense-in-Depth gegen Authorization-Code-Interception (siehe `OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`). PKCE bleibt parallel aktiv — der Token-Endpoint verlangt jetzt BEIDE Faktoren.
```

### Teil B — Keycloak-Admin-Anteil (Operator)

**B.1 — Anmelden in der Keycloak Admin Console**

URL prod: `https://profit-base.online/auth/admin/` (moved 2026-09-13, ADR-0166). Mit Admin-Credentials einloggen (aus `.env`: `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`).

**B.2 — Client `basetool-frontend` auf confidential umstellen**

Realm dropdown oben links → **`iri`** auswählen → Sidebar **Clients** → **basetool-frontend** anklicken.

**Tab "Settings"**:
- **"Client authentication"** Toggle: `OFF` → `ON`.
- **"Authorization"** Toggle bleibt `OFF` (wir nutzen Keycloak-Authorization-Services nicht).
- **"Authentication flow"** unverändert: `Standard flow` `ON`, `Direct access grants` `OFF`, `Implicit flow` `OFF`, `Service accounts roles` `OFF`.
- Unten **Save**.

Nach dem Save erscheint ein neuer Tab **"Credentials"** im Client-View.

**B.3 — Client Secret generieren**

Tab **"Credentials"**:
- **"Client Authenticator"**: `Client Id and Secret` (default, sollte schon stehen).
- **"Regenerate"**-Button neben dem Secret-Feld → bestätigen.
- Secret kopieren (lange zufällige Base64-String). Das ist der Wert für `KEYCLOAK_FRONTEND_CLIENT_SECRET`.

Das Secret wird in Keycloak verschlüsselt gespeichert; man sieht es danach nicht mehr im Klartext — nur Regenerate erzeugt ein neues. Also: jetzt direkt sicher in `.env` ablegen oder im Passwort-Manager.

**B.4 — PKCE in Keycloak hart erzwingen** (optional aber empfohlen)

Spring Security 6.x sendet PKCE automatisch nur wenn der Client public ist. Bei confidential clients muss man entweder im Spring-Code PKCE explizit aktivieren ODER Keycloak-seitig hart fordern.

Tab **"Advanced"** (im Client-View) → Sektion **"Proof Key for Code Exchange Code Challenge Method"** → auf `S256` setzen → Save.

Damit weist Keycloak jeden Authorization-Request ohne PKCE ab. Spring sendet PKCE dann wieder automatisch (es erkennt die Anforderung). So bekommt man die kombinierte PKCE + Secret-Auth, statt nur Secret allein.

**B.5 — `realm-export.json` aktualisieren** (falls vorhanden)

Falls auf dem Prod-Host eine `realm-export.json` als Source-of-Truth liegt, die nach Container-Recreate re-imported wird (`docker-compose.yml` mountet sie nach `/opt/keycloak/data/import/realm-export.json`), muss die Client-Definition dort auch aktualisiert werden. Sonst wird beim nächsten Realm-Import der manuelle Umstellung wieder überschrieben.

Konkret in der `clients`-Liste den `basetool-frontend`-Eintrag suchen und:

```diff
   "publicClient": true,
+  "publicClient": false,
+  "clientAuthenticatorType": "client-secret",
+  "secret": "<das gleiche secret wie in .env>",
   "standardFlowEnabled": true,
```

plus in `attributes`:

```diff
+  "pkce.code.challenge.method": "S256",
```

Die Datei ist gitignored und liegt nur auf Prod — Inhalt nicht ins Repo committen.

### Teil C — Deploy-Sequenz

**C.1 — Wartungsfenster ankündigen**

5–10 Minuten Login-Downtime einplanen (existierende Sessions laufen weiter, neue Logins schlagen kurzzeitig fehl).

**C.2 — Keycloak umstellen** (siehe B.2–B.4)

Wenn `realm-export.json` als Source-of-Truth: erst die Datei updaten (B.5), dann Keycloak-Container neustarten → liest die neue Config ein. Sonst nur die Admin-Console-Klicks.

**C.3 — Prod-`.env` updaten**

Auf dem Prod-Host:

```bash
# Backup
sudo cp /opt/iri/.env /opt/iri/.env.backup-$(date +%Y%m%d-%H%M%S)
# Edit
sudo $EDITOR /opt/iri/.env
# Eintrag hinzufügen / aktualisieren:
#   KEYCLOAK_FRONTEND_CLIENT_SECRET=<wert aus Schritt B.3>
```

**C.4 — Frontend-Container neu starten**

```bash
cd /opt/iri
docker compose pull frontend     # falls neue Image-Version mit dem Code-PR ausgerollt wird
docker compose up -d frontend
docker compose logs -f frontend | head -100
```

Im Log nach `Started FrontendApplication` und `o.s.s.o.client.OAuth2AuthorizedClientService` Meldungen schauen. Eine Fehlermeldung wie `InvalidClientException: Invalid client credentials` deutet auf einen Tippfehler im `.env`-Secret oder Mismatch mit Keycloak hin.

**C.5 — Smoke-Test**

- Inkognito-Browser → `https://profit-base.online` → Login-Button klicken.
- Weiterleitung zur Keycloak-Login-Seite, Credentials eingeben.
- Erfolgreicher Redirect zurück auf die Startseite mit gültiger Session.
- In Browser-DevTools → Network-Tab → bei der Redirect-Response auf `/login/oauth2/code/keycloak` den Token-Exchange-Request inspizieren (sichtbar nur im Backend, nicht im Browser — alternativ Keycloak Event-Log).
- Keycloak Admin Console → **Realm `iri`** → **Events** → **Login Events** → letzten Login finden → Type `CODE_TO_TOKEN` muss `client_secret_basic` als Authentifizierungs-Methode zeigen.

**C.6 — Rollback-Plan**

Falls in C.5 was schiefgeht:
1. Keycloak: Client `basetool-frontend` → Settings → "Client authentication" → `OFF` → Save.
2. Prod-`.env`: `KEYCLOAK_FRONTEND_CLIENT_SECRET`-Zeile auskommentieren.
3. Frontend Container restart (rollt automatisch auf die Vor-Migration-Image-Version, falls die noch im Compose-File steht — sonst manuelles Repinning auf den letzten guten Tag).

Mit dem Rollback ist man wieder auf public-Client + PKCE-only — funktional unverändert zum Vor-Migrations-Zustand.

---

## Validierung nach erfolgreichem Deploy

- [ ] `docker compose exec frontend env | grep KEYCLOAK_FRONTEND_CLIENT_SECRET` zeigt den Secret-Wert.
- [ ] Keycloak Admin → basetool-frontend → Settings → "Client authentication" `ON`.
- [ ] Keycloak Events → letzter erfolgreicher `LOGIN` Event aus dem Frontend, Auth-Methode `client_secret_basic`.
- [ ] Inkognito-Login durchläuft sauber.
- [ ] In Keycloak Events: kein `LOGIN_ERROR` mit Reason `invalid_client` in den letzten 15 Minuten.
- [ ] Backend-Service-Account (`backend-service`-Client, der `KEYCLOAK_ADMIN_CLIENT_SECRET` nutzt) ist unverändert funktional — nur der `basetool-frontend`-Client wurde berührt.
- [ ] Existierende Sessions sind nicht invalidiert (bestehende Login-Tokens bleiben gültig bis Ablauf).

## Risiken & Caveats

- **Secret-Leak-Risiko**: das Secret liegt jetzt im `.env` auf Prod und im `.env`-Backup. Die `.env` ist `chmod 600`, gitignored, im `.dockerignore`. Trotzdem ein zusätzlicher Geheim-Wert, der rotiert werden muss, wenn `.env` jemals leakt.
- **PKCE-Verlust ohne B.4**: wenn der "Code Challenge Method"-Toggle in Keycloak nicht auf `S256` steht, sendet Spring Security PKCE nicht mehr automatisch im confidential-Modus. Defense-in-Depth wäre dann reduziert auf nur-Secret. B.4 ist daher quasi nicht-optional.
- **Spring-Boot-Test-Kontexte**: jeder `@SpringBootTest` ohne den `client-secret`-Property-Stub in `application-test.yml` (A.4) bricht beim Context-Start. Falls A.4 vergessen wird, schlägt die CI fest zu — leichter Trigger zum Fix.
- **realm-export.json drift** (B.5): wenn die Datei nicht aktualisiert wird und Keycloak später re-imported wird (z.B. Disaster-Recovery), springt der Client zurück auf public und alle Logins schlagen fehl. Operator-Notiz im Runbook hinterlegen.

---

## Prompt für KI-Agenten

Folgendes Prompt einem KI-Agenten geben (z.B. neue Claude-Code-Session, oder einem anderen Agent mit Repo-Zugriff). Es ist self-contained und braucht keinen Kontext aus dieser Datei oder dem vorherigen Audit.

```
Audit-Finding M-6 (2026-05-20) im basetool-Repo umsetzen: den Frontend-OAuth2-Client
gegenüber Keycloak von public (PKCE-only) auf confidential (Client-Secret + PKCE)
umstellen.

Hintergrund: Das Frontend ist ein server-side Thymeleaf-SSR (kein SPA), kann also
ein Client-Secret sicher in einem Env-Var halten. PKCE allein ist RFC-konform aber
weniger defense-in-depth — wer den Authorization Code interceptet (kompromittierter
Reverse-Proxy, missgeleiteter Redirect, etc.) kann ihn einlösen. Mit Client-Secret
+ PKCE kombiniert reicht ein einzelner kompromittierter Layer nicht mehr.

WICHTIG: Die Migration hat zwei Teile, die in zwei verschiedenen Welten ablaufen
müssen. Setze nur Teil A (Code) um. Teil B (Keycloak Admin Console Klicks) und
Teil C (Deploy-Sequenz) gibst du mir am Ende als saubere Step-by-Step-Anleitung
zurück — ich führe die selber durch.

=== Teil A: Code-Anteil (du machst das) ===

1. `frontend/src/main/resources/application.yml`:
   - `client-authentication-method: none` -> `client_secret_basic`
   - Neue Zeile darunter: `client-secret: ${KEYCLOAK_FRONTEND_CLIENT_SECRET}`
   - KEIN Default-Fallback bei `${...}` — strikt wie SERVER_SSL_KEY_STORE_PASSWORD.

2. `frontend/src/main/resources/application-dev.yml`:
   - `client-secret`-Property hinzufügen, diesmal MIT Default-Fallback, damit
     `./gradlew :frontend:bootRun` lokal ohne Docker funktioniert:
     `client-secret: ${KEYCLOAK_FRONTEND_CLIENT_SECRET:dev-secret-placeholder}`
   - Nur dieses eine Property überschreiben, alles andere erbt von application.yml.

3. `frontend/src/main/resources/application-test.yml`:
   - In der `spring.security.oauth2.client.registration.keycloak`-Section ein
     statisches `client-secret: test-client-secret` ergänzen, damit @SpringBootTest-
     Kontexte starten.

4. `docker-compose.yml`:
   - In der `x-frontend: &frontend-template`-Definition (suche nach dem Anchor)
     ein neues Env-Var einfügen, analog zum bestehenden `KEYCLOAK_ADMIN_CLIENT_SECRET`
     im Backend-Template:
     `KEYCLOAK_FRONTEND_CLIENT_SECRET: ${KEYCLOAK_FRONTEND_CLIENT_SECRET:?KEYCLOAK_FRONTEND_CLIENT_SECRET must be set in .env}`
   - Die `:?…`-Fail-Fast-Syntax ist Pflicht (gleiches Pattern wie die anderen
     Secrets in dieser Datei).

5. `.env.example`:
   - Neuen Eintrag im Stil der bestehenden Secrets ergänzen, mit Kommentar zur
     Rotation:
     ```
     # Keycloak basetool-frontend OAuth2 client secret (Spring OAuth2 client login).
     # Generate / rotate in the Keycloak admin console:
     #   Realm "iri" -> Clients -> basetool-frontend -> Credentials -> Regenerate Secret.
     KEYCLOAK_FRONTEND_CLIENT_SECRET=CHANGE_ME
     ```

6. Tests:
   - `frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/config/SecurityConfigTest.java`
     und `RoleHierarchyTest.java` prüfen — falls dort hartcodierte Assertions auf
     `client-authentication-method: none` existieren, anpassen.
   - Ergänze einen Pinning-Test (z.B. in `SecurityHeadersTest` oder neu in
     `OAuth2ClientConfigurationTest`), der über das Spring `Environment` bestätigt:
     `client-authentication-method` ist `client_secret_basic` und `client-secret` ist
     ein nicht-leerer String. Damit fängt der Build eine Regression auf den
     public-client-Pfad sofort.

7. `CHANGELOG.md`:
   - Im `### Security`-Block unter `## [Unreleased]` (ganz oben, neueste zuerst)
     einen kurzen, terser Eintrag im Stil der bestehenden Audit-Finding-Einträge
     ergänzen. Verweise auf die `OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`-Datei
     für die Operator-Schritte.

8. Verifikation:
   - `./gradlew :frontend:spotlessApply :frontend:checkstyleMain :frontend:spotbugsMain :frontend:test`
   - Alle vorhandenen Frontend-Tests laufen weiter durch (das placeholder-Secret
     in `application-test.yml` reicht ihnen).
   - Falls Tests brechen, **nicht** die placeholder-Secrets auf irgendwas
     Production-ähnliches setzen — der String `test-client-secret` ist klar
     synthetisch (CLAUDE.md-Regel: keine production-secrets in Tests).
   - Am Ende `./gradlew check` laufen lassen — muss BUILD SUCCESSFUL liefern.

KEINE Keycloak-realm-export.json oder Prod-`.env` anfassen — beides liegt
außerhalb des Repos.

=== Teil B+C: Operator-Anteil (das musst du mir als Anleitung zurückliefern) ===

Generiere am Ende deiner Code-Änderungen einen Markdown-Block "Operator Runbook" mit
schritt-für-Schritt-Anleitungen für:

- B.1 Keycloak Admin Console öffnen (Prod-URL + Login mit den Admin-Credentials aus
  dem prod `.env`).
- B.2 Client `basetool-frontend` im Realm `iri` von public auf confidential
  umstellen (exakte Toggle-Pfade durch die Settings-Tabs).
- B.3 Client Secret regenerieren + sicher ablegen.
- B.4 PKCE per Advanced-Tab auf `S256` erzwingen (sonst sendet Spring Security
  PKCE nicht mehr automatisch im confidential-Modus, und Defense-in-Depth wäre
  reduziert auf nur-Secret).
- B.5 `realm-export.json` auf dem Prod-Host updaten (falls vorhanden; sonst nächster
  Realm-Import macht die manuelle Umstellung wieder rückgängig).
- C.1-C.5 Wartungsfenster ankündigen, .env updaten, Container restart, Smoke-Test.
- C.6 Rollback-Plan (wie ich's wieder rückgängig mache, falls C.5 schiefgeht).

Jeder Schritt soll genau einen klar abgeschlossenen Atom-Vorgang sein, sodass ich
ihn abhaken kann. Keine vagen "danach prüfen ob alles funktioniert" — konkrete
Befehle bzw. Klick-Pfade.

Am Ende eine kurze Validierungs-Checkliste (Konkret-Befehle: was muss ich
ausführen, was muss ich sehen).

Lieferung als Antwort: erst eine Zusammenfassung was du im Code geändert hast,
dann den Operator-Runbook-Block.
```

---

## Verwandte Dokumente

- [`MULTI_SQUADRON_PLAN.md`](MULTI_SQUADRON_PLAN.md) — referenziert für Pattern-Conventions.
- [`README.md`](README.md) — Sektion "Running the Local Test Stack" / Env-Vars.
- [`CHANGELOG.md`](CHANGELOG.md) — Audit-Findings 2026-05-20, `### Security`-Block.
- [`frontend/src/main/resources/application.yml`](frontend/src/main/resources/application.yml) — Ausgangs-Konfiguration.

