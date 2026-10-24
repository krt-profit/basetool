# UC-47 — Austausch: Installationen trennen, neu verbinden, Kontoabgleich, Korpus, Bestätigung, Sperre & Austritt

|                |                                                                                                                                              |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-47                                                                                                                                        |
| **Tag**        | `e2e`                                                                                                                                        |
| **Testklassen** | [`ExchangeConnectionsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ExchangeConnectionsE2eTest.java) · [`ExchangeSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ExchangeSyncE2eTest.java) · [`ExchangeDepartureE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ExchangeDepartureE2eTest.java) |
| **Spec**       | [`external-exchange.md`](../specs/external-exchange.md) (REQ-XCH-003, REQ-XCH-008, REQ-XCH-015, REQ-XCH-021, REQ-XCH-031, REQ-XCH-032) · [ADR-0225](../adr/0225-the-e2e-stack-runs-the-sandbox-keycloak-and-the-ingest-gateway.md) |

## Akteure

- **Drittanbieter-Client** `e2e-exchange-client`, gespielt von `ExchangeTestClient` wie in [UC-46](UC-46-austausch-rundlauf.md); jede Installation hat ihren eigenen P-256-Schlüssel.
- **Mitglieder**, je Klasse ein eigener Realm-Nutzer mit Standardrollen und `KRT Member`, über den Seeder in der IRIDIUM-Staffel und sonst von keinem Test verwendet: `test-exchange-2` (`ExchangeConnectionsE2eTest`), `test-exchange-3` (`ExchangeSyncE2eTest`), `test-exchange-departed` (`ExchangeDepartureE2eTest`).
- **Admin** `test-admin` — registriert den Client, schaltet den Austausch ein und sperrt den Client auf der Registerseite.
- **Keycloak-Administrator** des Stacks (Master-Realm, Wegwerf-Passwort aus `E2eStackExtension`) — entzieht dem austretenden Mitglied seine Rollen.

## Vorbedingungen

Nur im ephemeren Modus (`assumeTrue(STACK.managesStack())`). Jede Klasse ruft `ExchangeE2eSupport.prepare`: `exchange-e2e-seed.sql` (Blaupause „E2E Exchange Rifle", Material „E2E Exchange Metal"), Mitgliedschaft des Mitglieds, Registrierung des Clients (ein gesperrter wird wieder aktiviert) und globaler Schalter an. `ExchangeSyncE2eTest` spielt zusätzlich `exchange-corpus-e2e-seed.sql` ein: die zwölf Produkte, gegen die `ExchangeResolveCorpusTest` das Korpus auflöst, und den SCMDB-Alias „Lynx Arms".

## Abläufe

### Eine Installation trennen (`revokingOneInstallationLeavesTheOtherWorking`)

1. Zwei Device-Logins desselben Mitglieds, jede Installation mit eigener Bezeichnung.
2. Auf `/connected-apps` trennt das Mitglied eine der beiden Installationen („Trennen", bestätigen).
3. Der Client ruft mit beiden Installationen das Gateway auf; die getrennte erneuert zusätzlich ihr Token.

**Erwartet:** Die getrennte Zeile verschwindet ohne Neuladen, die andere bleibt. Das Gateway weist die getrennte Installation sofort mit `401 INSTALLATION_REVOKED` ab, auch mit dem erneuerten Token; die andere bekommt `200`.

### Neu verbinden nach dem Trennen der ganzen Anwendung (`aNewConnectionAfterAWholeClientDisconnectWorksAtOnce`)

1. Device-Login, danach „Anwendung trennen" auf `/connected-apps`.
2. Der alte Client ruft das Gateway auf.
3. Ein neuer Device-Login mit `offline_access` (neuer Schlüssel, neue Anmeldung, neue Zustimmung).

**Erwartet:** Der alte Client wird mit `401 CLIENT_REVOKED` abgewiesen, der erste Aufruf der neuen Verbindung wird sofort beantwortet.

### Kontoabgleich (`theAccountCheckAnswersUnknownMatchAndMismatchWithoutTheHandle`)

1. Das Mitglied hat kein RSI-Handle im Profil; der Client fragt `POST /exchange/v1/me/account-check`.
2. Das Mitglied speichert ein Handle (`PUT /api/v1/users/me/rsi-handle`); der Client fragt mit demselben Handle in Kleinbuchstaben und mit einem fremden.

**Erwartet:** `unknown`, dann `match` und `mismatch`; jede Antwort enthält nur `result`, nie das gespeicherte Handle.

### Sperre durch den Admin (`anAdminSuspensionAndReactivationReachTheGateway`)

1. Device-Login; der Admin sperrt den Client auf `/admin/exchange-clients` (bestätigen).
2. Der Client fragt das Gateway, bis es antwortet; der Admin aktiviert den Client wieder.

**Erwartet:** Das Gateway antwortet nach der Sperre mit `403 CLIENT_SUSPENDED` und nach der Aktivierung wieder mit `200` (Registerspiegel mit fünf Sekunden Cache, der Test wartet bis zu 90 s). Ein Fehlschlag lässt den Client nicht gesperrt zurück.

### Korpus-Rundlauf (`theCorpusReachesMeineBlueprintsAndTheFeedOfAnotherInstallation`)

1. Eine lesende Installation liest den Blaupausen-Snapshot bis zum Ende und merkt sich den Cursor.
2. Eine schreibende Installation löst alle 31 Namen aus `game-log-corpus-v1.json` in einem Aufruf auf und fügt die aufgelösten Produkte in einem Änderungssatz hinzu.
3. Die lesende Installation liest den Feed ab ihrem Cursor; das Mitglied öffnet „Meine Blueprints" (`/personal-inventory/blueprints`).

**Erwartet:** Die fünf Namen, die einem Produkt genau entsprechen, sind aufgelöst; jede Hinzufügung wird angewandt, steht im Feed der anderen Installation und als Zeile mit `data-source-client="e2e-exchange-client"` auf „Meine Blueprints".

### Große Änderung bestätigen (`aHeldMassRemovalAppliesOnlyOnceTheMemberConfirmsIt`)

1. Der Client bucht fünf Lose „E2E Exchange Metal" (Qualität 100 bis 500) mit je 5 SCU ein.
2. Ein Änderungssatz setzt alle fünf auf 0.
3. Das Mitglied öffnet den Pfad der `confirmationUrl` im Browser und klickt „Bestätigen".

**Erwartet:** Schritt 2 wird mit `409 MASS_CHANGE_CONFIRMATION_REQUIRED` angehalten und ändert nichts; die Seite zeigt die Zusammenfassung mit dem Namen des Clients, nach „Bestätigen" das Ergebnis, und alle fünf Lose sind leer.

### Austritt (`aDepartedMemberIsRefusedOnTheNextRequest`)

1. Device-Login; ein Aufruf wird beantwortet.
2. Der Keycloak-Administrator entfernt die direkten Realm-Rollen des Mitglieds (`KRT Member` und `default-roles-basetool`, die `KRT Member` ebenfalls enthält).
3. Das Mitglied meldet sich per Passwort-Grant am Backend an; der Login-Abgleich erkennt den Rollenverlust (`MemberDepartedEvent`), und `ExchangeDepartureService` beendet die Verbindung.
4. Der Client ruft das Gateway erneut auf.

**Erwartet:** `401 CLIENT_REVOKED` beim nächsten Aufruf.

## Sonderfälle & Lehren

- **Login- statt Roster-Abgleich.** Der Austritt läuft über den Login-Abgleich. Der Roster-Abgleich („Jetzt synchronisieren") gleicht jedes Konto des gemeinsamen Stacks ab und würde Zustand anderer Klassen verändern; sein Auslöser ist in `UserReconciliationServiceTest` abgedeckt.
- **`KRT Member` steckt auch in der Standardrolle.** Wer nur `KRT Member` entzieht, lässt die Rolle über `default-roles-basetool` bestehen; der Test entzieht beide.
- **Die Massenänderung zählt Lose, keine Blaupausen.** Bei Blaupausen zählt der aktuelle Bestand die Standard-Blaupausen mit, deren Zahl von anderen Klassen abhängt; die fünf Lose eines sonst unbenutzten Mitglieds machen die Schwelle (mindestens 5 und mehr als ein Fünftel) unabhängig davon.
- **Gemeinsame Schritte** (Device-Login, Registrierung, Warten auf den Registerspiegel, Änderungssätze) liegen in `ExchangeE2eSupport`, den auch [UC-46](UC-46-austausch-rundlauf.md) nutzt.
