# UC-42 — Admin: Systemeinstellungen, Audit-Log & Adminseiten-Smoke

|                |                                                                                                                                                                                                                                                                                                                                                              |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-42                                                                                                                                                                                                                                                                                                                                                        |
| **Tag**        | `e2e`                                                                                                                                                                                                                                                                                                                                                        |
| **Testklasse** | [`AdminSettingsInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AdminSettingsInPlaceE2eTest.java) · [`AuditLogE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AuditLogE2eTest.java) · [`AdminPagesSmokeE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AdminPagesSmokeE2eTest.java) · [`AdminMaterialCreateInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AdminMaterialCreateInPlaceE2eTest.java) |
| **Spec**       | [`audit.md`](../specs/audit.md) (REQ-AUDIT-001/-002/-004/-005) · [ADR-0037](../adr/0037-shared-multi-domain-activity-audit-log.md) · REQ-FE-001/-002                                                                                                                                                                                                          |

## Akteur

`test-admin` (ADMIN). Alle vier Klassen betreffen ADMIN-only-Seiten.

## Vorbedingungen

- Eingeloggte Admin-Session.
- Für das Audit-Log: eine per REST geseedete Bank-Einzahlung, damit der Bank-Tab mindestens eine Zeile vom Typ `DEPOSIT_BOOKED` hat.
- Der Adminseiten-Smoke seedet nichts.

## Auslöser

Der Admin speichert Systemeinstellungen, liest das Audit-Log oder öffnet eine der Admin-Datenpflegeseiten.

## Hauptablauf

### Systemeinstellungen zweimal hintereinander speichern (`AdminSettingsInPlaceE2eTest`)

1. `/admin/settings` öffnen, Reload-Marker setzen, `ageYellowDays` auf einen neuen Wert ändern und in place speichern (`POST /admin/settings` mit `X-Requested-With` → je Einstellung `PUT /api/v1/settings/{key}` über `window.krtFetch.write`).
2. Ohne Reload `ageYellowDays` erneut auf einen **anderen** Wert ändern und ein zweites Mal speichern.

### Einheitliches Audit-Log (`AuditLogE2eTest`)

3. Die Alt-URL `/admin/bank-audit` aufrufen.
4. Den Löschen-Dialog der Aufbewahrung öffnen (`audit-purge-open`) und ungenutzt wieder schließen.
5. Reload-Marker setzen, nach Ereignistyp `DEPOSIT_BOOKED` filtern (`audit-filter-event`, `audit-filter-apply`).
6. Auf den Tab „Lager" wechseln (`audit-tab-INVENTORY`, ein normaler Link) und dort nach Client `basetool-frontend` filtern (`audit-filter-client`).

### Adminseiten-Smoke (`AdminPagesSmokeE2eTest`)

7. Parametrisiert über `FrontendPageRoutes#ADMIN_SMOKE` (Modul `test-support`) jede Seite laden: `/members`, `/organisation/leitung`, `/admin/locations`, `/admin/material-aliases`, `/admin/uex-data`, `/admin/discord-registrations`, `/admin/sync-reports`, `/admin/p4k-import`, `/admin/announcement`, `/admin/notification-rules`, `/admin/org-structure`, `/admin/blueprints`, `/admin/personal-inventory`, `/admin/personal-blueprints`.

### Material anlegen in place (`AdminMaterialCreateInPlaceE2eTest`, #2002, FE-SEC-03 / FE-PERF-06)

8. `/admin/materials` öffnen, Reload-Marker setzen, das Anlege-Modal öffnen, einen pro Lauf eindeutigen Namen eintragen und den Anlegen-Knopf **doppelt** klicken.

## Erwartetes Ergebnis

- **Einstellungen:** Beide Speichervorgänge zeigen einen Erfolgs-Toast, keinen Fehler- oder Konflikt-Toast und keinen Reload-Bestätigungsdialog (`.krt-confirm-overlay`); der Marker überlebt, und das Backend hat den zweiten Wert gespeichert.
- **Audit-Log:** Die Alt-URL leitet auf `/admin/audit-log?domain=BANK` um; das Panel listet mindestens eine Zeile, und der Löschen-Dialog zeigt die Warnung, vorher ein Backup zu ziehen (REQ-AUDIT-004). Der Ereignisfilter läuft in place (Marker überlebt, URL trägt `eventType=DEPOSIT_BOOKED`) und listet weiter die Einzahlung. Der Client-Filter wird auf dem Bank-Tab ebenfalls angeboten (seit V238, REQ-AUDIT-005). Nach dem Tabwechsel trägt die URL `domain=INVENTORY`, und der Client-Filter läuft in place mit `clientId=basetool-frontend` in der URL.
- **Smoke:** Jede Seite antwortet mit **HTTP 200** und rendert die angemeldete Shell (`nav-logout`) — keine Umleitung zum Identity Provider, kein Fehler.
- **Material anlegen:** Genau **ein** `POST /admin/materials/ajax` verlässt den Browser (`krtFetch` sperrt den Knopf beim ersten Klick), die neue Zeile erscheint in `#materialsTable`, und der Marker überlebt — kein Reload.

## Sonderfälle & Lehren

- **Der Einstellungs-Test bewacht eine Klasse von Optimistic-Lock-Fehlern** (epic #571, #582): `SystemSettingService.updateSetting` gab nach einer echten Änderung eine veraltete Version zurück, und der zweite Schreibvorgang lief mit `OPTIMISTIC_LOCK` auf 409. Der Fix (`save` → `saveAndFlush`) lässt den AJAX-Zwilling die erhöhten Versionen zurückgeben, die die Seite in die versteckten `*Version`-Felder schreibt. Jeder Speichervorgang muss den Wert **wirklich** ändern — ein No-op-Speichern erhöht die `@Version` nicht und würde den Fehler nicht auslösen.
- **Die neue Materialzeile wird an ihrer Namenszelle erkannt** (korrigiert 2026-09-23). Jede Zeile der Tabelle trägt ein Auswahlfeld, dessen Optionen den ganzen Katalog aufzählen — auch das gerade angelegte Material. Ein `hasText` über die ganze Zeile traf deshalb jede Zeile (34 statt 1), und der Test war seit #2002 in jeder Matrixzelle rot, obwohl das Produkt richtig arbeitete. Der Locator filtert jetzt auf `td:first-child`.
- **Das Löschen wird nur geöffnet, nie ausgeführt.** Ein echtes Löschen würde die geseedete Einzahlung entfernen, auf die der Rest des Laufs baut.
- **Die ADMIN-only-Grenze des Audit-Logs** prüft [UC-32](UC-32-kartellbank-antraege-berechtigungen.md) (`BankPermissionsE2eTest`).
- **Der Smoke ist eine Auswahl aus einem Katalog.** Seiten mit eigenem Flow (`/admin/settings`, `/admin/materials`, `/admin/special-commands`, `/admin/default-blueprints`, `/admin/bank`, `/admin/audit-log`, `/admin/mission-data`) fehlen absichtlich. `PageRouteCatalogueTest` prüft, dass jeder Eintrag eine existierende Seitenroute ist; welche Seiten hineingehören, bleibt eine Auswahl. Weil die Seiten ADMIN-gegated sind, meldet sich die Klasse ausdrücklich als `test-admin` an und trägt `@Tag("e2e")` statt `smoke` — anders als [UC-07](UC-07-kernseiten-smoke.md), das auch gegen ein Staging mit Nicht-Admin-Nutzer läuft.
