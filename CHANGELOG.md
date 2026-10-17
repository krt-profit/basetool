# Changelog

## [Unreleased]

### Security

- **Management-Ports: Metriken nur noch mit Scrape-Zugang.** Auf den Management-Ports von Backend, Frontend und Ingest bleibt nur `/actuator/health` offen; `prometheus`, `info` und `loggers` verlangen den Basic-Auth-Zugang `MONITORING_SCRAPE_USER`/`MONITORING_SCRAPE_PASSWORD` und sind ohne ihn gesperrt. Vorher konnte jeder Nachbar-Container im selben Netz die Metriken lesen. Prometheus sendet `scrape_password`, das deshalb zu `MONITORING_SCRAPE_PASSWORD` passen muss (Benutzer `monitoring`) (REQ-OBS-005, ADR-0265).

### Added

- **Audit: Änderung des Mitgliedsrangs wird protokolliert.** Ändert ein Admin den Rang 1–20 eines Mitglieds, schreibt das Audit-Log unter „Rollen & Mitglieder“ das Ereignis „Mitgliedsrang geändert“ mit altem und neuem Rang; ein unveränderter Rang erzeugt keinen Eintrag (REQ-AUDIT-001, REQ-ROLE-007).

- **Profitberechnung: Sortieren per Spaltenkopf.** Material, je SCU, Marge, Kapital und Max Profit sortieren per Klick auf den Spaltenkopf, ein zweiter Klick dreht die Reihenfolge um – auch auf dem Handy über eine Leiste über der Liste. Die Platzziffer bleibt der Rang nach Max Profit; die Sortierung bleibt bei einer neuen Berechnung erhalten und wird pro Browser gemerkt (REQ-UI-030).

- **Material-Detailseite: UEX-Stand je Preis.** Die Terminal-Tabelle zeigt in der neuen Spalte „UEX-Stand“, wann UEX den Preis des Terminals zuletzt aktualisiert hat; Preise, die älter als sieben Tage sind, sind als veraltet markiert. Die Kachel „Aktualisiert“ heißt jetzt „Letzter UEX-Sync“; `GET /api/v1/materials/{id}/prices` liefert dafür `uexUpdatedAt` (REQ-UI-015).

- **Testsystem im Footer erkennbar.** Auf dem Testsystem zeigt der Footer jeder Seite ein gelbes „Testsystem"-Schild, damit niemand dort versehentlich echte Arbeit erledigt; Produktion bleibt unverändert. Geschaltet über die neue Umgebungsvariable `APP_INSTANCE_TEST_SYSTEM` (Frontend, REQ-UI-029).

### Changed

- **Materialdetail: Sortieren über die Spaltenköpfe.** Die Tabelle „Preise und Terminals" sortiert per Klick auf Terminal, Ort, Verkauf oder Einkauf; ein zweiter Klick dreht die Richtung um – auch auf dem Handy über eine Leiste über der Liste. Der Umschalter „Verkauf · Einkauf" entfällt, Standard bleibt „Verkauf, höchster zuerst" (REQ-UI-015, REQ-UI-030).

- **Monitoring: CSP-Verstöße nennen ihre Ursache.** Der Fehler-Beacon meldet bei einem `trusted-types`-Verstoß den versuchten Policy-Namen und bei jedem CSP-Verstoß, ob der Code von der eigenen Seite, einer Browser-Erweiterung oder anderswoher stammt; `basetool_client_csp_violation_total` trägt dafür das Label `code_origin` (Dashboard 07, Panel 85). Grundlage für die Trusted-Types-Umstellung auf `enforce` (REQ-SEC-064, REQ-FE-022).

- **Backend: keine Feldinjektion mehr.** `UserMapper`, `MissionMapper` und `InventoryItemMapper` erhalten ihre Abhängigkeiten über paketinterne `@Autowired`-Setter statt über Felder; die neue ArchUnit-Regel `noFieldIsAutowired` lässt kein `@Autowired`-Feld mehr zu (REQ-SEC-003). Kein Verhaltensunterschied.

### Fixed

- **Fehler beim Speichern: richtige Meldung und erneute Anmeldung.** 53 AJAX-Aktionen (Lager, Aufträge, Raffinerie, Missionen, Benachrichtigungen, Mitglieder, Profil, Organigramm, Admin-Stammdaten u. a.) fangen Fehler nicht mehr selbst als pauschalen 500/502 ab: Konflikte wie „gleichzeitig geändert" (409) behalten ihren Status und ihre Meldung, und eine abgelaufene Anmeldung führt wieder zur Keycloak-Anmeldung statt zu einem Fehler-Toast (Frontend, REQ-FE-036).
- **Abgelaufene Anmeldung beim Seitenaufruf.** Seiten, deren Abschnitte einzeln nachladen (Startseite, Lager, Aufträge, Raffinerie, Missionen, Bank, Blueprints, Admin-Seiten u. a.), leiten bei abgelaufener Anmeldung wieder zur Keycloak-Anmeldung weiter, statt die Seite mit Fehlerhinweisen in jedem Abschnitt anzuzeigen (Frontend, REQ-FE-036).

- **Einsatz-Detailseite: nicht geladene Auswahllisten werden gemeldet.** Schlägt das Laden einer Auswahlliste (Jobtypen, Crew-Funktionen, Organisationseinheiten, Frequenz- oder Schiffstypen, Schiffe für Einheiten) fehl, nennt ein Hinweis über den Tabs die betroffenen Listen, statt die Auswahl stillschweigend leer zu lassen; das Frontend protokolliert jeden Fehlschlag als WARN. Die Listen laden jetzt parallel (Frontend, REQ-MISSION-030).

- **Frontend: Hostname-Prüfung zum Backend standardmäßig an.** Ohne gesetztes `INTERNAL_TLS_VERIFY_HOSTNAME` prüft das Webtool den Namen im Backend-Zertifikat jetzt wie der Ingest-Gateway, statt die Prüfung stillschweigend auszulassen; Compose und Quadlet setzten `true` bereits, `dev`/`test` bleiben unverändert (Frontend-Konfiguration, REQ-SEC-070).

- **Mitglied bearbeiten: Rang passt nicht zur Officer-Rolle.** Ein Rang außerhalb des Bereichs der Keycloak-Rolle (Officer 1–12, Mitglieder 13–20) wird nicht mehr als „ungültiges Argument“ abgelehnt, sondern am Rang-Feld mit dem Hinweis, welche Rolle das Mitglied noch hat und dass sie zuerst in Keycloak geändert werden muss; unter dem Rang steht der erlaubte Bereich. Neuer API-Fehlercode `RANK_OUTSIDE_ROLE_RANGE` (REQ-ROLE-007, ADR-0264).

- **Lager-Übersicht: Umschalter „Globales Lager | Mein Lager“ ohne Leerraum.** Die Segmente sind wieder genau so breit wie ihre Beschriftung; die Kopfzeilen-Navigation vererbte ihren Abstand an jeden Segment-Umschalter, der als `<nav>` gesetzt ist (Design-System-CSS, REQ-UI-028).

- **Verbundene Anwendungen (Admin): Layout.** Die Tabelle der registrierten Anwendungen passt wieder in die Seitenbreite, statt seitlich zu scrollen, und die Berechtigungs-Checkboxen im Formular sind bei langen Bezeichnungen alle gleich groß (REQ-XCH-003).

- **Verbundene Anwendungen (Admin): Bestätigungen benennen ihre Aktion.** Die Bestätigungsdialoge der Seite beschriften ihren Knopf mit „Erweitern", „Sperren", „Ein-/Ausschalten" bzw. „Zurücknehmen" statt mit „Löschen", obwohl nichts gelöscht wird (REQ-XCH-003).

## [v1.16.5](https://github.com/krt-profit/basetool/releases/tag/v1.16.5) - 2026-10-17

### Added

- **Datenaustausch: gemeinsamer Lagerbestand der eigenen Einheiten.** Verbundene Anwendungen mit der neuen Berechtigung `exchange.orgstock.read` lesen über `GET /exchange/v1/org-stock` den gemeinsamen Lagerbestand jeder Einheit, der das Mitglied direkt angehört – je Einheit summiert, ohne Namen, mit den für Aufträge und Missionen reservierten Mengen. Einwilligungsseite, „Verbundene Anwendungen" und Datenschutzerklärung nennen die Berechtigung (REQ-XCH-042, ADR-0257, Migration V295).

- **Datenaustausch: Änderungs-Feed für den Einheitsbestand.** Eine Anwendung liest nach dem ersten Abruf nur noch die geänderten Bestände der eigenen Einheiten statt jedes Mal alles neu; Umbuchungen zwischen persönlich und gemeinsam, ein Wechsel der Einheit und Reservierungen erscheinen darin, Bestände fremder Einheiten nicht (REQ-XCH-042, Migration V296).

- **Datenaustausch: Ein- und Ausbuchen für Logistiker.** Mit der neuen Berechtigung `exchange.orgstock.write` bucht ein Logistiker einer Einheit über eine verbundene Anwendung Bestand für ein Mitglied der Einheit in deren gemeinsames Lager ein und Einträge daraus aus – mit denselben Regeln, Benachrichtigungen und Lager-Protokolleinträgen wie im Web; die Mitgliederliste dafür lässt Mitglieder aus, die ihren Namen zurückhalten. Jede Buchung zählt zum Tageskontingent der Anwendung (REQ-XCH-045, Migration V298).

- **Datenaustausch: Verkaufen, Umbuchen, Notiz und „gestohlen" für Logistiker.** Ein Logistiker einer Einheit verkauft über eine verbundene Anwendung Einträge des gemeinsamen Lagers (Missionsanteile am Erlös wie im Web), bucht sie an einen anderen Ort oder an ein Mitglied einer anderen seiner Logistiker-Einheiten um und ändert Notiz und „gestohlen" – mit den Regeln, Benachrichtigungen und Lager-Protokolleinträgen des Webs (REQ-XCH-045, Migration V299).

- **Datenaustausch: Reservierungen für Logistiker.** Ein Logistiker einer Einheit reserviert über eine verbundene Anwendung Teile gemeinsamer Einträge für Aufträge und Missionen, ändert und entfernt Reservierungen – mit den Regeln und Lager-Protokolleinträgen des Webs. `GET /exchange/v1/org-stock/targets` liefert dafür die Aufträge und Missionen, die das Web zur Auswahl anbietet (REQ-XCH-045, Migration V301).

- **Datenaustausch: Rückgängig machen und Massenänderungs-Schutz für den Einheitsbestand.** Ein Logistiker macht unter „Verbundene Anwendungen" auch die Änderungen rückgängig, die seine Anwendung am gemeinsamen Lager vorgenommen hat; ein seither geänderter Eintrag bleibt und wird gemeldet. Die Rücknahme durch die Administration umfasst sie ebenfalls. Nimmt ein Stapel zu viel aus dem Lager einer Einheit, muss der Logistiker ihn im Browser bestätigen (REQ-XCH-046, Migration V302).

- **Lager: Pool wechseln.** Ein Logistiker zweier Einheiten verschiebt im Globalen Lager einen gemeinsamen Eintrag – ganz mit seinen Reservierungen oder eine Teilmenge aus dem freien Bestand – in das Lager einer anderen Einheit, der der Inhaber angehört; der Inhaber wird benachrichtigt. Verbundene Anwendungen können das mit der Op `move-pool` (REQ-INV-059, Migration V300).

- **Datenaustausch: Auftragsdetails für Logistiker.** Mit der neuen Berechtigung `exchange.demand.details` erhält ein Logistiker einer Einheit im Materialbedarf zusätzlich die offenen Aufträge dieser Einheit mit Nummer, Auftraggeber, Kommentar und offenen Positionen – nie, wer daran arbeitet. Für alle anderen bleibt der Bedarf anonym; die Datenschutzerklärung nennt die Berechtigung (REQ-XCH-047, Migration V303).

- **Datenaustausch: Einzeleinträge für Logistiker.** Mit der neuen Berechtigung `exchange.orgstock.details` sieht ein Logistiker einer Einheit im Einheitsbestand die einzelnen Einträge mit Halter, Notiz und Reservierungen; wer seinen Namen zurückhält, erscheint ohne Name und Notiz. Je Anwendung wird ein eigenes Pseudonym statt der Nutzer-ID übertragen (REQ-XCH-043, REQ-XCH-044, Migration V297).

- **Profil: Namen von verbundenen Anwendungen zurückhalten.** Der neue Schalter „Meinen Namen nicht an verbundene Anwendungen weitergeben" im Profil (standardmäßig aus) speichert in place, ist Teil des Datenexports und wird im Bereich „Verbundene Anwendungen" protokolliert; die Datenschutzerklärung nennt, welche Namen eine Anwendung erhält. Wirksam wird er mit dem Einheitsbestand für Logistiker (REQ-XCH-044, ADR-0257, Migration V294).

### Fixed

- **Lager: Ausbuchen und Umbuchen zeigen Einsätze mit Name und Datum.** Unter „Herkunft der Menge" stand bei einem Einsatz statt seines Namens dessen interne ID, vor allem nach einer gerade erst vorgenommenen Zuordnung. Jetzt erscheinen Name und geplantes Datum, ein nicht mehr auffindbarer Einsatz als „Unbekannter Einsatz" (REQ-INV-027).

- **Monitoring: SC-Wiki-Sync sprengt das `uri`-Limit nicht mehr.** Backend und Ingest bereinigen den `uri`-Tag ausgehender Anfragen schon beim Start; vorher legte der Laufzeit-Timer `http.client.requests.active` je Wiki-Seite oder Feed-Cursor einen neuen Tag-Wert an, bis Micrometer weitere Werte verwarf (REQ-OBS-006).

- **Passwort vergessen: klarer, was einzugeben ist.** Die Seite fragt nach E-Mail-Adresse oder Benutzername und erklärt, dass bei Discord-Konten der Discord-Benutzername gilt (nicht Server-Nickname oder Anzeigename), und zwar der von der ersten Anmeldung (Keycloak-Theme, REQ-SEC-082).

- **Anmeldung über Discord: Mitglieder kommen wieder ins Tool.** Seit dem Admin-OTP für Discord-Logins (13.10.) endete jede Discord-Anmeldung eines Nicht-Admins mit „Ungültiger Benutzername oder Passwort“. Der Post-Login-Flow erhält einen Pflichtschritt „Allow access“ vor dem Admin-OTP-Block; `scripts/harden-keycloak-realm.py` legt ihn an und meldet sein Fehlen (REQ-SEC-082).

## [v1.16.4](https://github.com/krt-profit/basetool/releases/tag/v1.16.4) - 2026-10-15

### Added

- **Hangar: Einheit eines Schiffs nachträglich ändern.** Die neue Zeilenaktion „Einheit ändern" in „Meine Schiffe" ordnet ein eigenes Schiff einer der eigenen Einheiten zu oder nimmt es mit „Keine Einheit" heraus, etwa ein Schiff, das eine verbundene Anwendung ohne Einheit angelegt hat. Jede Zeile zeigt ihre Einheit; der Bearbeiten-Dialog zeigt „Zuordnen zu" nicht mehr (REQ-HANGAR-009, ADR-0258, Migration V290).

- **Hangar: Einheit mehrerer Schiffe auf einmal ändern.** In „Meine Schiffe" lassen sich Schiffe per Checkbox markieren; die Auswahlleiste ordnet alle markierten Schiffe mit einer Aktion einer eigenen Einheit zu oder setzt „Keine Einheit". Schiffe, die schon dort sind, werden übersprungen; ein fremdes Schiff in der Auswahl bricht die ganze Aktion ab (REQ-HANGAR-010).

- **Admin: Hangar eines Mitglieds.** Die neue Seite `/admin/members/{id}/hangar`, verlinkt in „Mitglied bearbeiten", zeigt Admins die Schiffe eines Mitglieds und lässt sie dort hinzufügen, bearbeiten und löschen; das Mitglied wird über jede Änderung benachrichtigt (REQ-HANGAR-011).

- **Admin: Einheit der Schiffe eines Mitglieds ändern.** Im Hangar eines Mitglieds ordnen Admins ein Schiff per Zeilenaktion oder mehrere über die Auswahlleiste einer Einheit des Mitglieds zu oder setzen „Keine Einheit". Das Mitglied erhält eine Benachrichtigung, bei mehreren Schiffen eine Sammelmeldung (REQ-HANGAR-012, Migration V291).

- **Hangar: Einheiten-Tab und Admin-Hangar aktualisieren sich live.** Der Tab „Org-Einheit" zeigt neue, geänderte, gelöschte und in eine andere Einheit verschobene Schiffe ohne Neuladen, auch wenn eine verbundene Anwendung sie geschrieben hat. Änderungen im Admin-Hangar eines Mitglieds erscheinen sofort in dessen „Meine Schiffe" und umgekehrt (REQ-HANGAR-013, ADR-0260).

- **Datenaustausch: eigene Einheiten lesen.** Verbundene Anwendungen mit der neuen Berechtigung `exchange.orgunits.read` lesen über `GET /exchange/v1/me/org-units` die Einheiten, denen das Mitglied direkt angehört (Name, Kürzel, Art), etwa um sie zur Auswahl anzubieten. Einwilligungsseite, „Verbundene Anwendungen" und Datenschutzerklärung nennen die Berechtigung (REQ-XCH-048, Migration V292).

- **Datenaustausch: Schiffe nennen ihre Einheit.** Der Schiffs-Feed `GET /exchange/v1/me/ships` liefert je Schiff `orgUnit` (ID, Name, Kürzel, Art; fehlt ohne Einheit) und den Zähler `orgUnitVersion`. Ein Einheitenwechsel und das Umbenennen einer Einheit bringen die betroffenen Schiffe in den nächsten Abruf, damit Anwendungen keinen veralteten Namen behalten (REQ-XCH-017, Migration V293).

### Fixed

- **Datenaustausch: Anwendungen setzen die Einheit eines Schiffs.** Ein `upsert` in `POST /exchange/v1/me/ships/changes` kann mit `orgUnit` eine eigene Einheit oder `null` für keine wählen; zum Ändern sendet er `orgUnitVersion` mit. Ohne `orgUnit` bleibt alles wie bisher: Ein neues Schiff erhält die einzige Einheit des Mitglieds, ein bestehendes behält seine. Eine fremde Einheit (`ORG_UNIT_NOT_ALLOWED`) oder ein veralteter Zähler (`ORG_UNIT_VERSION_CONFLICT`) lehnt nur diese Operation ab (REQ-XCH-017).

- **Datenaustausch: Rückgängig stellt auch die Einheit eines Schiffs wieder her.** Hat eine Anwendung ein Schiff in eine andere Einheit verschoben oder gelöscht, bringt „Rückgängig" es in seine frühere Einheit zurück. Gehört das Mitglied dieser Einheit nicht mehr an, bleibt die aktuelle Einheit, ohne dass das Rückgängigmachen scheitert (REQ-XCH-022).

## [v1.16.3](https://github.com/krt-profit/basetool/releases/tag/v1.16.3) - 2026-10-14

### Fixed

- **Bedarf & Spenden: kompaktere Tabellen.** Die Zeilenaktionen („Spenden", Bearbeiten, „⋯") stehen auf Desktop nebeneinander statt untereinander, die Spaltenüberschriften bleiben beim Scrollen sichtbar, und ein „⋯"-Menü, das unten nicht mehr in Tabelle oder Fenster passt, klappt nach oben auf – das gilt für alle „⋯"-Menüs (REQ-SUP-021).

## [v1.16.2](https://github.com/krt-profit/basetool/releases/tag/v1.16.2) - 2026-10-14

### Added

- **Bedarf & Spenden (Beschaffung).** Einheiten, die Bedarf melden dürfen, veröffentlichen, was ihnen fehlt; Mitglieder sagen Materialspenden zu (auch direkt aus der eigenen Lagerzeile), die empfangende Einheit bestätigt sie ins Einheitenlager. Dazu Statistik pro Monat, drei Benachrichtigungen (Zusage, Erhalt, Ablehnung), Prüfprotokoll-Bereich „Bedarf & Spenden" und Verwaltungsseite `/admin/supply`; per Schalter `supply.enabled` standardmäßig aus (REQ-SUP-001…020, ADR-0252, ADR-0253, Migrationen V284–V286).

- **Ankauf (Handelsbeauftragte).** Handelsbeauftragte bestellen die Bereichsleiter und Bereichskoordinatoren der in der Verwaltung gewählten Bereiche (standardmäßig Profit); bestellte Handelsbeauftragte kaufen für freigegebene Bedarfe bei externen Partnern ein: Geschäft mit Risikoklasse, Mittel als Bankanträge (Vier-Augen-Prinzip, Monatslimit, Budgetdeckel), Lieferungen, die die Einheit annimmt und ins Einheitenlager bucht, Abrechnung mit Rückfluss des Restbetrags, Pause-Schalter, Monatsbericht mit CSV und Forumtext, acht Benachrichtigungen, Prüfprotokoll-Bereich „Ankauf" und Verwaltungsseite `/admin/purchasing`; per Schalter `purchasing.enabled` standardmäßig aus (REQ-PUR-001…028, ADR-0255, Migrationen V287–V289).

### Changed

- **Bank: Vier-Augen-Prinzip für jeden Buchungsantrag.** Wer einen Antrag gestellt hat, kann ihn weder freigeben noch bestätigen (409 `BANK_FOUR_EYES_REQUIRED`); eine zweite Person entscheidet. Ablehnen bleibt möglich, direkte Buchungen bleiben unverändert, Admins sind ausgenommen (REQ-BANK-062, ADR-0251).

- **Edge: kein 429 mehr auf Skripte, Schriften und Bilder beim schnellen Seitenwechsel.** Die statischen Dateien (`/js/`, `/css/`, `/fonts/`, `/images/`, `/logos/`) zählen jetzt in einem eigenen, großzügigen Limit (200 r/s, Burst 600), Seiten und API gelten 50 r/s mit Burst 150 statt 20/80; der Login-Token-Endpunkt bleibt unverändert begrenzt (REQ-SEC-023, ADR-0256).

### Fixed

- **Monitoring: SC-Wiki-Abrufe erzeugen keine `uri`-Tag-Flut mehr.** Der Einzelabruf von Items und Blueprints trug die UUID im Pfad, sodass `http.client.requests` des Backends pro Ressource einen neuen `uri`-Wert anlegte und das Limit von 100 mit einer Warnung riss; der Pfad ist jetzt eine URI-Vorlage (`/api/items/{id}`).

## [v1.16.1](https://github.com/krt-profit/basetool/releases/tag/v1.16.1) - 2026-10-13

### Changed

- **Datenauskunft: sprechende Dateinamen und lesbares JSON.** PDF und JSON heißen jetzt „Datenexport <Kontoname> <JJ-MM-TT>“ (auch beim Export durch einen Admin), und die JSON-Datei ist eingerückt statt einzeilig (REQ-SEC-058).

### Fixed

- **Verbundene Anwendungen: Karte nutzt die volle Breite.** Mit nur einer verbundenen Anwendung blieb die rechte Hälfte leer, weil das Raster leere Spalten reservierte; es klappt sie jetzt weg (`connected-apps.css`).

- **Datenschutzerklärung: Inhaltsverzeichnis ohne doppelte Kapitelnummern.** Die Kapitel zeigten „1. 1. Datenschutz auf einen Blick“, weil die Listennummerierung zusätzlich zur Nummer im Titel erschien; sie ist jetzt abgeschaltet (`legal.css`).

## [v1.16.0](https://github.com/krt-profit/basetool/releases/tag/v1.16.0) - 2026-10-13

### Added

- **Monitoring: CSP-Verstöße nach Direktive.** Neue Metrik `basetool_client_csp_violation_total{directive}` und Panel 85 im Betriebs-Dashboard; ein `ClientErrorSpike` für `csp_violation` nennt damit die verletzte Direktive, ohne dass der Logger auf Produktion angehoben werden muss.

- **Star-Citizen-Links: Kategorien lassen sich einklappen.** Standardmäßig sind alle offen; deine Auswahl bleibt lokal im Browser gespeichert.

- **Star-Citizen-Links: Lootmaster und VerseIntel.** Lootmaster (Crafting-Blueprints, Wikelo-Trades, Missionen) steht unter „Datenbanken & Crafting", VerseIntel (Wissensgraph zu Entwickleraussagen, Patch Notes und Bug-Reports) unter „Universum & Community".

- **Ablehnungen nennen ihren Grund im Log.** Die WARN-Zeile einer 422 `PRODUCTION_ALLOCATION` trägt jetzt, welche der fünf Prüfungen abgelehnt hat, samt Material-/Lagerzeilen-ID und Mengen (`reason`, `materialId`, `consumed`, …) als abfragbare Felder in Loki – nie den Request-Body. Die Antwort an den Client bleibt unverändert.

### Changed

- **Android-App: Update-Pflicht bis Build 19.** Der Server verlangt jetzt mindestens Build 20 (App v0.6.1) und meldet ihn als neueste Version; Builds bis 19 (v0.6.0 und älter) sehen den Update-Bildschirm. Build 19 scheitert beim Speichern von Auszahlungsart und Blaupausen-Freigabe mit einem Konflikt, Build 20 nicht.

- **Profil: Auszahlungsart und Blaupausen-Freigabe sind entkoppelt.** Beide Einstellungen liegen in eigenen Tabellen (Migration V283) mit eigener Version; Speichern der einen macht Profil, RSI-Handle und die andere Karte nicht mehr veraltet. Die alten Spalten bleiben bis zu einer späteren Version.

- **Lesbarere Bedienelemente (Design-System 2026-10).** Buttons, Eingabefelder, Auswahlen und Seitenzahlen haben eine Füllung und einen sichtbaren Rand (mindestens 3:1); Umschalter wie „Kommend / Vergangen / Alle“ oder „Globales / Mein Lager“ sind ein gemeinsames Segment-Element, Ein/Aus-Optionen ein Schalter; Listen-Seiten liegen mit Toolbar, Tabelle und Seitenzahlen auf einer Fläche, der Filter-Button zeigt aktive Filter mit eckigem Zähler.

### Fixed

- **Keycloak-Härtung: das Binden des Post-Login-Flows an Discord scheiterte.** `scripts/harden-keycloak-realm.py` schickte den Identity-Provider-Update ohne `providerId` (`Invalid identity provider id [null]`); Bindung, Rücknahme und „Configure OTP aktivieren" laufen jetzt im Merge-Modus, der Selbsttest bildet die Server-Regel nach (REQ-SEC-082).

- **Dialoge schließen sich nicht mehr beim Markieren per Ziehen.** Wer in einem Feld Text markiert und die Maustaste außerhalb des Dialogs loslässt, behält den Dialog offen (gilt für alle Dialoge, z. B. das Verkaufen im Lager).

- **Lager: der Verkaufsbetrag ist beim Öffnen leer.** Das Feld wurde mit dem Wert des vorherigen Verkaufs vorbefüllt; es wird bei jedem Öffnen des Ausbuchen-Dialogs geleert.

- **Mobil: Auswahlgruppen füllen wieder die Breite.** Die Variante `krt-segmented--block` (Umschalter über die volle Zeile, z. B. Rundungsmodus in den Systemeinstellungen) war seit dem Design-System-Update 2026-10 durch einen Tippfehler im Selektor wirkungslos; die Systemeinstellungen scrollten dadurch auf dem Handy seitwärts.

- **Mein Lager: die Tabelle scrollt am Desktop nicht mehr seitwärts.** Die Baumtabelle füllt wieder ihren Container; nur auf schmalen Bildschirmen (bis 1024 px) darf sie breiter als der Bildschirm sein.

- **Kachel-Raster: alle Kacheln sind gleich ausgerichtet.** In der Material-Übersicht (und anderen Kachel-Rastern) saßen alle Kacheln außer der ersten um 1 rem tiefer, weil die Regel für aufeinanderfolgende Karten auch im Raster griff.

- **Leitung: die Einheiten-Liste links ist einheitlich eingerückt.** Die Gruppen (Organisationsleitung, Bereiche, Staffeln, Spezialkommandos) saßen je nach Namenslänge unterschiedlich weit rechts; dasselbe Layout-Problem hatte das Inhaltsverzeichnis der Datenschutzerklärung.

- **Monitoring: zwei Fehlalarme.** `ExternalFetchErrors` zählt unvollständige SC-Wiki-Seitendurchläufe nicht mehr mit (neues Label `reason`, Wert `census`; sie haben je Lauf einen festen Sockel und lösten bei Neustart oder Upstream-Umbau aus). `SystemdUnitFailed` ignoriert `dnf-makecache.service`, das bei einem 5xx des Spiegelservers beim nächsten Lauf von selbst heilt.

## [v1.15.0](https://github.com/krt-profit/basetool/releases/tag/v1.15.0) - 2026-10-11

### Added

- **Deploy: veraltete Host-Skripte fallen auf.** Das Release bringt die Hashes der Host-Skripte mit (`host-scripts.sha256`); `deploy.sh` vergleicht sie bei jedem Tick (`basetool_host_scripts_drift`, Alarme `HostScriptDrift` und `HostScriptsCheckStale`) und verweigert ein Release, dessen Apply-Skripte (`deploy.sh`, `lib/`, `render-env-d.py`) auf dem Host andere sind, bevor sich etwas ändert. Notausgang: `--force` oder `IRI_REQUIRE_CURRENT_SCRIPTS=false`. Der Vergleich vor jeder Promotion ist jetzt Pflicht (`docs/deployment.md`).

### Changed

- **Nicht rückgängig zu machen: die Migration V282.** Sie löscht die abgelösten Qualitätsspalten `job_order_material.min_quality`, `job_order_item_material.quality_requirement` und `material_claim.quality_requirement` samt zugehöriger Indizes und Prüfregeln. Ein reiner Code-Rollback auf ein Release vor v1.14.0 ist danach nicht möglich; der Rückweg ist die Wiederherstellung des Backups, das unmittelbar vor der Promotion gezogen wird.

- **Android-App: Mindest- und neueste Version sind jetzt 19.** Mit dem gemeinsamen API-Schnitt erscheint die App v0.6.0 (versionCode 19); wer eine ältere App nutzt, wird mit dem Release zum Update aufgefordert und kann die App erst nach dem Update wieder benutzen (REQ-API-020).

- **REST-API, Welle 1 (nur Web, keine App-Änderung).** Die Administrations-Endpunkte liegen jetzt je Fachbereich unter `/api/v1/<bereich>/admin/**` (z. B. `/users/admin`, `/catalog/admin`, `/connected-apps/admin`), die Rollen unter `/api/v1/roles`, die Benachrichtigungsregeln unter `/api/v1/notifications/admin/rules`; ein einziges ADMIN-Gate steht vor allen Fachregeln. Das Duplikat `PUT /admin/users/{id}/attributes` und die beiden Ping-Endpunkte entfallen. Teil des gemeinsamen API-Schnitts (REQ-API-023).

- **REST-API, Welle 2 (Identität und Organisationseinheiten).** Mitgliedsbezogene Endpunkte wandern aus `/users` und `/me` zu ihrem Fachbereich (`/org-units/me/…`, `/blueprints/me/sharing`, `/missions/me/payout-preference`, `/announcements/{id}/read`, `/bank/members/search`); die Hierarchie liegt unter `/org-units`, die Leitungsansicht unter `/org-chart/leadership`, das Ändern und Löschen einer Kommandogruppe unter ihrer Staffel. Die alten App-Pfade antworten `410 APP_UPDATE_REQUIRED`; die App erscheint mit dem gemeinsamen API-Schnitt neu (versionCode 19).

- **REST-API, Welle 3 (Einsätze).** Die Einsatz-Endpunkte verlieren das Suffix `/slim`; die Einsatzsuche ist jetzt `GET /api/v1/missions?q=…` (der Parameter `query` heißt `q`). Finanzbuchungen werden unter `/api/v1/missions/{missionId}/finance-entries` angelegt, geändert und gelöscht, Lagerbestand und Raffinerieaufträge eines Einsatzes über `?missionId=` gelesen. `PUT /missions/{id}` und `POST /missions/{id}/participants/add` entfallen. Die alten App-Pfade antworten `410 APP_UPDATE_REQUIRED`; die App erscheint mit dem gemeinsamen API-Schnitt neu (versionCode 19).

- **REST-API, Welle 4 (Kartellbank).** Die Bank-Endpunkte für Offiziere, Leiter und Mitglieder wandern von `/api/v1/org-units/bank/…` nach `/api/v1/bank/org-units/…`; das Kontostandsziel der Bankmitarbeiter wird mit `PUT` statt `PATCH` gesetzt (`/api/v1/bank/accounts/{id}/balance-target`, gleicher Inhalt). Die alten App-Pfade antworten `410 APP_UPDATE_REQUIRED`; die App erscheint mit dem gemeinsamen API-Schnitt neu (versionCode 19).

- **REST-API, Welle 5 (Aufträge und Spielgegenstände).** Die beiden Gegenstandskataloge (`/orders/item-catalog` und `/inventory/item-catalog`) sind ein Katalog: `GET /api/v1/game-items` mit `q`, `orderable=true` für die bestellbaren Gegenstände und einer gemeinsamen Antwort (`id`, `name`, `manufacturer`, `kind`); er wird nur noch revalidiert statt als `no-store` ausgeliefert, die Rollen der beiden Vorgänger gelten unverändert. Blueprint-Liste und Materialableitung liegen unter `/game-items/{id}/blueprints` und `/blueprints/{id}/derivation`, die Lagerzuordnungen eines Auftrags unter `/orders/{id}/allocations[/{inventoryItemId}|/orphaned]`, die Raffinerie-Ausbeuten unter `/locations/{locationId}/refinery-yields`. Die alten App-Pfade antworten `410 APP_UPDATE_REQUIRED`; die App erscheint mit dem gemeinsamen API-Schnitt neu (versionCode 19).

- **REST-API, Welle 6 (der kleine Rest).** Die Ankündigung liegt unter `/api/v1/announcements/current` (Lesen mit `204`, wenn keine aktiv ist, Schreiben, Löschen und die Admin-Ansicht `…/admin`), die Materialgesuche der Materialbörse unter `/api/v1/material-exchange/requests/…`; das Zurückziehen eines Angebots über die Lagerzeile ist `POST /api/v1/material-exchange/offers/deactivate?inventoryItemId=`. Die Job-Alters-Schwellen liefert `GET /api/v1/orders/settings`, den Rundungsmodus der Raffinerie `GET /api/v1/refinery-orders/settings`; `GET /api/v1/settings/{key}` bleibt der Admin-Speicher dahinter. Raffinerie-Aufträge werden mit eigenen Anfrage-Objekten angelegt und geändert (ohne `id`, Besitzer, Gewinn und Staffel); für ein anderes Mitglied bestellt die Logistik über `POST /api/v1/refinery-orders/users/{userId}`. Der veraltete Fleetview-Import `POST /api/v1/hangar/import/fleetview` ist entfernt (im Web kommen Schiffslisten über `POST /api/v1/hangar/import/ships`, der Import-Knopf heißt jetzt „Schiffsliste importieren“; der eigene Fleetview-Import der Android-App entfällt mit Version 19), und mit ihm die Deprecation-Header-Mechanik. Alte Pfade antworten `410 APP_UPDATE_REQUIRED` (App) oder `404`; die neue Android-Version 19 ist Voraussetzung.

- **Einstellungen lesen nur noch Admins.** `GET /api/v1/settings` und `GET /api/v1/settings/{key}` (der rohe Einstellungsspeicher) verlangen die Rolle ADMIN wie das Schreiben; Mitglieder und die App lesen die Schwellen und den Rundungsmodus über `GET /api/v1/orders/settings` und `GET /api/v1/refinery-orders/settings` (REQ-SEC-083).

- **Einstellungsspeicher nicht zwischenspeicherbar.** Die Admin-Lesezugriffe auf `/api/v1/settings` antworten mit `Cache-Control: private, no-store`.

### Fixed

- **Einsatz: Mit-Manager hinzufügen zeigte einen Fehler, obwohl es klappte.** Das Frontend las die Antwortliste des Backends als Text und warf einen Dekodierungsfehler (HTTP 500 im Browser), obwohl der Manager bereits eingetragen war; die Antwort wird jetzt verworfen.

## [v1.14.0](https://github.com/krt-profit/basetool/releases/tag/v1.14.0) - 2026-10-10

### Added

- **Benachrichtigungen zu verbundenen Anwendungen.** Wer eine Anwendung verbunden hat, erfährt, wenn sie gesperrt oder wieder aktiv ist, eine neuere Version braucht, eine Fähigkeit verliert oder wenn der Austausch ganz abgeschaltet wird (REQ-XCH-040, -041, Migration V281, #2414).

- **Benachrichtigungen zu Hangar und Blueprints.** Wessen Schiff einem Einsatz zugeteilt wird, erfährt es; fällt ein gelöschtes Schiff aus einem geplanten Einsatz, hören die Einsatzleitung und der Verantwortliche der Einheit davon. Wer Ausgerüstet-Markierungen zurückgesetzt bekommt oder wessen Hangar oder Blueprints ein Admin ändert oder leert, wird benachrichtigt (REQ-HANGAR-005…008, Migration V280, #2414).

- **Benachrichtigungen zur Organisation.** Admins erfahren, wenn nach einer Leitungsänderung die OFFICER-Rolle eines Mitglieds nicht zu seinen Posten passt; die Leitung einer Einheit erfährt, wenn ein Mitglied ausgeschieden ist, und die des übergeordneten Bereichs, wenn dadurch ein Leitungsposten frei wird (REQ-ORG-029, -030, Migration V279, #2414).

- **Benachrichtigungen zur Bank.** Wer einen freigegebenen Antrag bestätigen kann, erfährt, dass er bereit ist; Bankmitarbeiter hören, wenn ihre Berechtigung auf ein Konto erteilt, geändert oder entzogen wird. Auszahlungen, Halterübertragungen und Direktbuchungen von Bankpersonal melden sich bei den Betroffenen (Konto-Verantwortliche), und die Bankleitung erfährt, wenn ein deaktivierter Halter noch Geld hält (REQ-BANK-057…060, Migration V278, #2414).

- **Benachrichtigungen zu Materialbörse und Lager.** Wer an einem Angebot Interesse angemeldet oder zugesagt hat, ein Gesuch zu beliefern, erfährt, wenn das Angebot nicht mehr verfügbar oder das Gesuch zurückgezogen ist; wessen Bestand jemand anderes ausbucht oder verkauft, wird benachrichtigt (REQ-MARKET-021, -022, REQ-INV-056, Migration V277, #2414).

- **Benachrichtigungen zur Raffinerie.** Der Besitzer erfährt, wenn ein Raffinerieauftrag fertig ist und abgeholt werden kann, und wenn jemand anderes den Auftrag ändert, storniert oder einlagert, auch auf wessen Lager die Ausbeute gebucht wurde (REQ-REFINERY-023, -024, Migrationen V275/V276, #2414).

- **Keycloak-Härtung als Skript.** `scripts/harden-keycloak-realm.py` setzt die Runbook-Schritte 2 (Passwort
  vergessen), 11 (OTP für Admins, auch über Discord) und 12 (Sitzungsfenster) trocken vorab prüfbar und mit
  Rücknahme um; die Sitzungsfenster stehen jetzt in `scripts/keycloak/session-windows.json`, die der Provisioner
  mitliest und neben `external-clients.json` auf den Host gehört. Alle offenen Schritte nur für den Betreiber:
  `docs/OWNER_STEPS_2026-10.md` (REQ-SEC-082).

- **Benachrichtigungen zu Aufträgen.** Wer einen Auftrag übernimmt, an den er übergeben wurde, erfährt es (Offiziere, Leads und Logistiker der neuen Einheit); die anfragende Einheit hört, wenn ihr Auftrag abgeschlossen, abgelehnt oder gelöscht wurde. Wer einem Auftrag zugewiesen wird, und wessen Materialzusage durch eine Änderung oder Rückstufung entfällt, wird benachrichtigt (REQ-ORDERS-041…044, Migration V274, #2414).

- **Benachrichtigungen zu Einsätzen und Operationen.** Teilnehmer erfahren, wenn ein Einsatz verschoben,
  abgesagt oder gelöscht wird, werden 24 Stunden und eine Stunde vorher erinnert und bekommen den offenen Check-in
  gemeldet; wer von jemand anderem hinzugefügt oder entfernt wird, erfährt es ebenso wie neue Verantwortliche und die
  Auszahlung einer Operation. Die Einsatzleitung hört, wenn ein Teilnehmer abspringt oder ein Einsatz nie beendet
  wurde, Einsatzmanager und Offiziere, wenn eine Operation abgeschlossen und die Auszahlung fällig ist. Neue
  Variable `APP_MISSIONS_NOTICES_OVERDUE_AFTER` (REQ-MISSION-021…029, Migrationen V272/V273, #2414).

- **Benachrichtigungen abbestellen.** Unter Profil → Benachrichtigungen schaltest du jede Art von
  Benachrichtigung einzeln ab; eine abbestellte Art landet weder im Posteingang noch in der App.
  Die Löschantrags-Hinweise und die Warnung vor einer neuen Anwendungsverbindung bleiben immer
  aktiv. Auch Teil des Datenexports (REQ-NOTIF-027, Migration V271, #2414).

- **Zeitgesteuerte Benachrichtigungen (Grundlage).** Ein Dienst im Minutentakt kann künftig
  Hinweise auslösen, hinter denen keine Nutzeraktion steht (Erinnerungen, abholbereite
  Raffinerieaufträge); er läuft nur auf einer Instanz und meldet sich im Monitoring (Alarm
  `NotificationTimedStale`). Neue Variablen `APP_NOTIFICATIONS_TIMED_ENABLED` und
  `APP_NOTIFICATIONS_TIMED_INTERVAL` (REQ-NOTIF-026, #2414). Der Dienst meldet höchstens 100
  Hinweise pro Lauf und nur noch Fälle der letzten 14 Tage (Einsatz nie beendet) bzw. 7 Tage
  (Raffinerieauftrag fertig), sodass Altbestand die Posteingänge nicht flutet; ein Rückstau wird über
  mehrere Läufe abgearbeitet.

- **Kein stiller Verlust von Benachrichtigungen.** Ein Ereignisschwall über die Warteschlange der
  Benachrichtigungen hinaus (etwa eine Admin-Massenaktion) wartet jetzt auf Platz statt Hinweise zu
  verwerfen. Neue Metriken `basetool_notification_created_total` und
  `basetool_notification_executor_rejected_total` mit den Alarmen `NotificationCreationFlood` und
  `NotificationExecutorRejected` (REQ-NOTIF-029).

- **Benachrichtigungsregeln erreichen Gruppen.** Der Regel-Editor bietet vier neue Empfängerarten
  (Einsatz-Teilnehmer, Einsatzleitung, Inhaber einer verbundenen Anwendung, Ereignis-Adressaten) und
  die Rolle „Einheitsleitung"; eine Nachricht lässt sich außerdem nur für einzelne Empfänger
  wegräumen (REQ-NOTIF-024/-025, #2414).

- **Benachrichtigungen räumen auf und folgen Änderungen.** Entschiedene Registrierungen und
  abgeschlossene, abgelehnte oder gelöschte Aufträge verschwinden aus allen Posteingängen. Ein
  geänderter Buchungsantrag ersetzt die alte Nachricht durch eine mit dem neuen Betrag. Neue
  Kontoverantwortliche erfahren davon samt wartender Freigaben, und offene Anträge wandern zu ihnen
  (REQ-NOTIF-018/-023, REQ-BANK-034/-056, Migration V269).

- **Benachrichtigung bei Lager-Umbuchungen auf ein anderes Mitglied.** Wer Bestand auf dich umbucht,
  löst eine Benachrichtigung mit Material, Qualität, Menge und Ort aus; bucht jemand anderes deinen
  Bestand weg (z. B. ein Logistiker), erfährt auch der bisherige Besitzer davon. Eine
  Sammel-Umbuchung erzeugt eine Nachricht pro Empfänger (REQ-INV-055, Migration V267).

- **Layout-Prüfung um die Seitenmuster erweitert (Phase 5).** Die Geräteklassen-Prüfung meldet jetzt
  auch seitlich scrollende Karten am Smartphone, eine Hauptaktion außerhalb des ersten Bildschirms,
  mehr als eine Hauptaktion im Seitenkopf, verschachtelte `.hud-box`en und Text im alten Grau.
  Tabellenzeilen-Links tragen einheitlich die Test-ID `row-link` (REQ-UI-009, REQ-UI-027).

- **Startseite: „Angemeldet" bei den nächsten Einsätzen.** In „Nächste 7 Tage" trägt jeder Einsatz,
  für den man selbst angemeldet ist, den Chip „Angemeldet". Die Missionsliste der API liefert dafür
  pro Zeile das Feld `signedUp` für den Aufrufer (REQ-MISSION-012).

- **Profitberechnung nennt die Terminals der Route.** Jede Zeile zeigt „Kauf <Terminal> · <Preis>"
  und „Verkauf <Terminal> · <Preis>", der Ort des Terminals erscheint als Tooltip. Die API liefert
  dafür vier neue Felder je Zeile (REQ-UI-027).

- **Materialbörse im neuen Muster.** Tabs „Angebote · Gesuche" mit „Alle · Meine" statt vier Tabs,
  eine Schaltfläche „Angebot erstellen" bzw. „Gesuch erstellen" mit Auswahl Material/Item statt vier
  Buttons, Filter als Popover mit Chips und Sortiermenü. Alte Links auf die Börse funktionieren
  weiter (REQ-MARKET-009/-018).

- **Startseite und Formulare im neuen Muster (Phase 2).** Die Startseite zeigt die nächsten 7 Tage als
  Liste mit Datumsblock, daneben Information und die neuesten Benachrichtigungen. Neuer Auftrag,
  Einbuchen, Mitglied bearbeiten (mit Reitern), Systemeinstellungen, Information und
  Benachrichtigungsregeln haben nummerierte Abschnitte und eine fixierte Aktionsleiste (REQ-UI-027).

- **Rechtliches, Organigramm, Verbundene Anwendungen und Schiffsdaten im neuen Muster.** Diese
  Seiten haben Seitenkopf und Karten statt HUD-Box; die Datenschutzerklärung hat ein
  Inhaltsverzeichnis, das Organigramm ist auf dem Smartphone eine eingerückte Liste, und „Alle
  unfitted" sitzt bei den Schiffsdaten im „⋯"-Menü (REQ-UI-027).

- **Profil als eine Einstellungsseite.** Abschnitts-Navigation links, eine Speichern-Leiste, die nur
  bei Änderungen erscheint und alle geänderten Abschnitte speichert.

- **Einsatz- und Operationsdetail im neuen Muster.** Der Einsatz hat drei Reiter (Übersicht ·
  Teilnehmer & Einheiten · Finanzen & Auszahlung); statt des Reiters „Verwaltung" öffnet
  „Bearbeiten" einen Bearbeitungsmodus, „Anmelden" ist die Hauptaktion, Löschen liegt im „⋯"-Menü.
  Die Operation zeigt eine Kennzahlenleiste und vier Reiter (Übersicht · Einsätze · Auszahlung ·
  Finanzen) und wird in einem Dialog bearbeitet (REQ-MISSION-004/011).

- **Hangar: Versicherung als „Keine · Monate · LTI".** Statt einer Liste mit 122 Einträgen; Monate
  als Zahl von 1 bis 120.

- **Hangar: eine Seite mit Tabs „Meine Schiffe" · „Org-Einheit".** Die Org-Einheitsübersicht ist
  jetzt der zweite Tab (Schiffstypen als Baumzeilen mit Einsatzbereitschafts-Balken); „Meine
  Schiffe" filtert mit „Alle · Bereit · Nicht bereit" und zeigt „n von m einsatzbereit". Import,
  Home-Location und „Hangar leeren …" sitzen im Seitenkopf (REQ-HANGAR-001/002).

- **Listenseiten im neuen Muster (Phase 1).** Einsätze, Operationen, Benachrichtigungen, Aufträge,
  Materialbedarf, Lager, Sammelübersichten, Materialien, Mitglieder, Beförderung und alle
  Admin-Listen haben einen Seitenkopf mit Zähler, eine Werkzeugleiste mit Suche, Segment-Schalter und
  Filter-Popover, entfernbare Filter-Chips und Tabellen, deren Zeile der Link ist und die auf dem
  Smartphone stapeln; Status sind übersetzt, leere Listen zeigen einen Leerzustand (REQ-UI-027).

- **Mein Inventar: Blueprints mit Dialog und Craft-Status.** „Blueprints hinzufügen" öffnet einen
  Dialog, „SC Extractor laden" und „Alle löschen …" liegen im ⋯-Menü; die Seite hat eine Suche,
  „Alle · Craftbar" und „Raffinerie einrechnen". Jede Zeile zeigt Status-Punkt und „3×", das Detail
  eine Kennzahl und die Zutaten mit Bedarf, Vorrat und Qualität (REQ-INV-008/048).

- **Blueprint-Verfügbarkeit: Besitzer als Chips, Suche nach Personen.** Die ersten vier Besitzer
  stehen direkt in der Zeile, „+ n weitere" klappt den Rest auf; die Suche findet auch Besitzer
  innerhalb der eigenen Einheiten (REQ-INV-012/013).

- **Einsätze und Operationen: Zeitraum „Kommend · Vergangen · Alle".** Ersetzt die Checkbox
  „Vergangene anzeigen"; die gespeicherte Auswahl wird übernommen.

- **Beförderung und Einsatzdaten im neuen Muster.** „Themenbereiche verwalten" und „Einsatzdaten
  verwalten" sind Listen mit Detailbereich, deren Auswahl als Link erhalten bleibt; die
  Rangvoraussetzungen stehen als Matrix Rangsprung × Themenbereich. „Beförderungssystem" zeigt den
  Rangpfad als Stufen, „Meine Bewertungen" den Fortschritt zum nächsten Rang (REQ-PROMO-002/003).

- **Benachrichtigungen: „Ungelesen · Alle" und Links.** Die Seite zeigt zunächst nur Ungelesenes;
  Benachrichtigungen zu Aufträgen, Materialbörse, verbundenen Anwendungen und Admin-Vorgängen
  führen direkt zur Seite (REQ-NOTIF-022).

- **Kartellbank: Kontodetail mit Kennzahlen und Reitern.** Beide Kontodetailseiten zeigen Saldo,
  Ziel, ± 30 Tage und Buchungen als Kacheln, darunter die Reiter „Buchungen" und „Konto-Info" bzw.
  „Ziel & Sichtbarkeit"; die Historie hat „30 Tage · 90 Tage · Zeitraum …" und die Spalte „Saldo
  nach Buchung", einklappbare Bereiche entfallen. Auf der Org-Einheits-Bank heißt der Reiter jetzt
  „Anträge an unsere Konten", und „Laufende Anträge" zeigt den Freigabeweg.

- **Buchungsanträge: Status als Segment.** „Ausstehend · Bestätigt · Abgelehnt · Alle" ersetzt die
  Status-Checkboxen, „n warten auf dich" zählt die entscheidbaren Anträge; wartet ein Antrag auf
  eine Freigabe, steht „wartet auf …" in der Zeile. Die KRT-Freigaben sind eine Stufen-Leiste.

- **Auftragsverwaltung: „Meine · Zu bearbeiten · Alle".** Wer beides sehen darf, wechselt zwischen
  eigenen und allen Aufträgen; „Zu bearbeiten" zeigt die Aufträge, für die die eigene Einheit als
  bearbeitende Einheit zuständig ist. Dafür nimmt `GET /api/v1/orders` den neuen Parameter
  `toProcess` an (REQ-ORDERS-040).

- **Auftragsdetail im neuen Muster.** Oben zeigt „Geliefert / benötigt" mit Balken den Fortschritt;
  die Reiter richten sich nach der Auftragsart (Material: Bedarf · Übergaben · Bearbeiter ·
  Verknüpft; Item: Items · Materialien · Herstellung · Übergaben · Bearbeiter · Verknüpft), leere
  Reiter entfallen, Übergaben laufen über „Übergabe erfassen" (REQ-ORDERS-026).

- **Neues Erscheinungsbild der Website, Grundlage (Phase 0).** Fließtext in Lato 400 statt 300, nur
  noch die Seitentitel orange, Zwischenüberschriften weiß; dazu neue Bausteine für Seitenkopf mit
  „⋯“-Menü, Werkzeugleiste mit Filter-Chips, Segment-Schalter, Listentabellen, die auf dem Smartphone
  stapeln, und Formulare mit fixierter Aktionsleiste. Breakpoints nur noch 768/1024/1440 px
  (REQ-UI-027, REQ-UI-009, ADR-0242).

- **Mein Lager und Globales Lager im neuen Muster.** Filter im Popover mit Chips, „Alle · Persönlich
  · Gemeinsam" statt zweier Haken; Ausbuchen, Umbuchen und Einheit ändern für markierte Einträge
  stehen in einer Auswahlleiste, die erst bei einer Auswahl erscheint. „Globales Lager leeren" liegt
  im „⋯"-Menü (REQ-INV-034/036/037/046).

- **Eigenes internes OpenAPI-Dokument für die Exchange-Relay-Schnittstelle.** Die 14 Operationen
  unter `/api/v1/exchange/**` stehen jetzt in `exchange-relay.openapi.json` mit eigener
  Aktualitätsprüfung statt in `openapi.json`; der Vergleich mit dem letzten Release prüft beide
  Dokumente. Keine Verhaltensänderung (REQ-XCH-039).

- **Leitung als Liste mit Detailbereich.** Links der Einheitenbaum (OL · Bereiche · Staffeln ·
  Spezialkommandos) mit Suche nach Einheiten und Personen, rechts die gewählte Einheit mit „Mitglied
  hinzufügen" und den Reitern „Mitglieder · Kommandogruppen". Rang und Kommandogruppe speichern
  sofort bei Auswahl, und die Mitglieder eines Spezialkommandos werden direkt auf „Leitung"
  verwaltet (REQ-ROLE-004).

- **Aufträge: Qualität bei Übergabe und Herstellung selbst wählen.** Braucht ein Auftrag ein Material in
  mehreren Qualitätsstufen, wählst du bei der Übergabe je Position die Stufe (vorbelegt, änderbar). Die
  Herstellung schlägt die niedrigste passende Qualität vor (REQ-ORDERS-038/039).

- **Aufträge: Qualitätsstufen als pflegbarer Katalog.** Admins legen Stufen mit Code, Mindestqualität
  und deutscher/englischer Bezeichnung unter „Qualitätsstufen“ an; neue Stufen brauchen kein Release.
  „Keine“ und „Gut (650+)“ bleiben unverändert (REQ-ORDERS-036, ADR-0241, Migration V261–V264).

- **Handel im neuen Muster.** Die Preis-Übersicht zeigt, wie alt die UEX-Preise sind, markiert je
  Material den besten Verkauf (grün) und Einkauf (blau) und die Spanne; die Material-Detailseite hat
  vier Kennzahlen und eine nach Verkaufspreis sortierte Terminal-Tabelle mit Ort; die
  Profitberechnung ist nach Max Profit sortiert und nummeriert.

- **Backend: Modulkarte und eingefrorene Modulkopplung (Guard G-09).** Eine Domain-Map ordnet jede
  Backend-Klasse einem der 26 Zielmodule zu; ein ArchUnit-`modules()`-Test friert die heutigen 138
  Kanten gegen die Rangordnung ein, neue scheitern. Spring Modulith 2.1.1 läuft nur im Testumfang
  (REQ-MOD-001…005).

- **Raffinerie im neuen Muster.** Die Liste teilt sich in „Läuft · Abholbereit · Abgeschlossen ·
  Alle" mit Zählern; Suche nach Besitzer, Ort, Methode oder Material und das Blättern laufen
  serverseitig über alle Seiten. Anlegen und Detail sind ein nummeriertes Formular mit Statuskarte
  und „Ausbeute einlagern" als Hauptaktion (REQ-REFINERY-019).

- **Berechtigungsmatrix des Backends als geprüfte Datei.** `authorization-matrix.txt` hält für jede
  Operation die entscheidende URL-Regel und das wirksame `@PreAuthorize` fest, dazu die
  Service-Gates; `AuthorizationMatrixTest` schlägt bei jeder Abweichung fehl (REQ-SEC-074).

- **Sicherheitsausdrücke werden beim Build geprüft.** Jede Bean-Referenz in `@PreAuthorize` muss im
  Kontext mit Namen und Parameterzahl auflösbar sein, die Sicherheits-Beans tragen feste Namen, und
  kein Gate darf über `this` umgangen werden (REQ-SEC-075, REQ-SEC-076).

- **Metrik und Alarm für nicht auswertbare Sicherheitsausdrücke.** Ein solcher Fehler bleibt ein
  `400 ILLEGAL_ARGUMENT`, wird aber als `basetool_security_expression_failures_total` gezählt und
  löst `SecurityExpressionEvaluationFailed` aus (REQ-OBS-020).

- **Backend-Wächter für Daten und Transaktionen (Modularisierung, Phase 0).** Tests prüfen jetzt, dass
  jede Tabelle genau einem Modul gehört, Trigger und natives SQL fremde Tabellen nur über gelistete
  Ausnahmen erreichen, jede Mitgliedsreferenz in Löschung, Datenauskunft und Kontozusammenführung
  geregelt ist, Audit nie nach dem Commit geschrieben wird und Mutatoren keine gecachte Instanz
  verändern (REQ-DATA-020…022, REQ-AUDIT-007). Keine Verhaltensänderung.

- **Datenaustausch: der eingefrorene Vertrag wird beim Bauen geprüft.** Backend-Antworten werden gegen die
  veröffentlichten Schemas und Fixtures geprüft, jede Gateway-Route hat eine festgeschriebene Antwort, die
  gemeinsamen Bezeichner von Backend, Gateway und Frontend stehen an einer Stelle, und ein PR am Austauschpfad
  braucht das Label `e2e` (REQ-XCH-036…-038). Die Refinery-Entwurfsroute hat einen eigenen Request-Typ mit
  identischem JSON; das Verhalten bleibt unverändert.

- **Ingest-Gateway: nach Zuständigkeit paketiert, mit Strukturregeln.** Die Klassen liegen jetzt in 15
  Paketen nach Aufgabe (`edge`, `auth`, `gate`, `relay`, …) statt nach Art; ein ArchUnit-Test verbietet
  Paketzyklen, ausgehende HTTP-Aufrufe außerhalb von `relay` und Redis außerhalb von `registry`, `store`
  und `handoff` (REQ-INGEST-013/-014). Reine Verschiebung, das Verhalten bleibt unverändert.

- **Keycloak-Provider: Registrierungen und Keycloak-Version werden beim Bauen geprüft.** Ein Test lädt
  alle sechs Service-Registrierungen samt Provider-ID, und `repo-lint` schlägt fehl, wenn die
  Keycloak-Version im Katalog und das Keycloak-Image in Compose, Quadlet und Sandbox verschiedene
  Minor-Versionen nennen (REQ-OPS-040). Die Klassen liegen jetzt in `discord`, `gate` und `exchange`;
  das Verhalten bleibt unverändert.

- **Frontend: Routen, Rechte und Sitzungstypen sind gegen Verschiebungen gesichert.** Eine
  committete Routen-/Rechte-Tabelle, eine Pflicht-`@PreAuthorize` pro Handler, die statische Prüfung
  aller `T(…)`-Verweise und View-Namen in Templates und eine aus dem Code abgeleitete Liste der
  Sitzungstypen lassen den Build scheitern, statt dass ein verschobener Controller oder ein Formular
  still ein Recht oder Flash-Werte verliert (REQ-FE-025…027). Organigramm, Schiffsdaten und
  „Ankündigung gelesen" tragen ihr `isAuthenticated()` jetzt selbst; das Verhalten ist unverändert.

### Changed

- **Nicht rückgängig zu machen: die Migrationen V261–V265.** `V262`–`V264` setzen `quality_tier_id` auf
  `NOT NULL` (Auftragsmaterial, Auftrags-Artikelmaterial, Materialanspruch), `V264` löscht den alten
  Unique-Index von `material_claim`, `V265` begrenzt jede Qualität per `CHECK` auf 0–1000 und bricht den
  Backend-Start bei einem Wert außerhalb ab. Ein reiner Code-Rollback auf v1.13.7 ist danach nicht möglich
  (Schreibzugriffe auf Aufträge schlagen fehl); der Rückweg ist die Wiederherstellung des Backups, das
  unmittelbar vor der Beförderung gezogen wurde (`docs/RELEASE_1.14.0_PRODUCTION_RUNBOOK.md`).

- **UEX und SC Wiki werden nur über externe Adressen angesprochen.** Der Abruf lehnt Namen ab, die auf
  interne, Loopback- oder Link-Local-Adressen auflösen, folgt keiner Weiterleitung und läuft höchstens
  vier (UEX) bzw. zwei (SC Wiki) Aufrufe gleichzeitig. Eine auf einen internen Host zeigende
  `app.uex`- oder `app.scwiki`-URL funktioniert damit nicht mehr (REQ-SEC-081).
- **Frontend-Code nach Fachbereichen gepackt (Betrieb).** Controller, DTOs und Formulare liegen in
  `frontend.<bereich>.web` / `.model`, der Kern in `frontend.kernel.*`; die Logger-Kategorien aller
  Frontend-Klassen ändern sich. Nur nach
  einem Release mit der exakten Session-Liste (F2) ausrollen: eine noch offene Weiterleitung verliert
  beim Deploy einmalig ihre Flash-Werte, die Anmeldung bleibt (REQ-FE-032, `deployment.md`).
- **Auswahlfelder nach abgelaufener Sitzung führen zur Anmeldung.** Ist die Sitzung abgelaufen, zeigt
  eine Mitglieder-, Material- oder Kontosuche nicht mehr stumm „keine Treffer", sondern leitet zur
  Anmeldung weiter; dasselbe gilt für alle anderen nachgeladenen Listen und Downloads. Alle Lesezugriffe
  laufen über `krtFetch.get` / `getJson` (REQ-FE-031).

- **Unterstützte Browser: Chrome 122, Firefox 131, Safari/iOS 18.4 oder neuer** („Baseline 2025",
  ADR-0239). Typprüfung und ESLint prüfen die Skripte jetzt auf ES2025 (REQ-FE-018).

- **Gewinnberechnung: Ort des Terminals in der Route.** Der Ort steht jetzt in Klammern hinter dem
  Terminal („Kauf TDD (Hurston · Lorville) · 20") statt nur im Tooltip, damit er auch auf dem
  Smartphone sichtbar ist (REQ-UI-027).

- **Abhängigkeiten aktualisiert.** Flyway 13.9.0, Bucket4j 8.21.0, commons-lang3 3.21.0, Jackson 2.22.3
  und 3.2.3 sowie json-schema-validator 3.0.8. Lombok und google-java-format bleiben bewusst stehen.

- **Monitoring-Images aktualisiert.** Alloy v1.20.1, Tempo 3.1.0 (neue Blöcke im Format vParquet5) und
  redis_exporter v1.93.0, jeweils per Digest gepinnt. Konfiguration und Dashboards bleiben unverändert;
  Compose und Quadlet-Units sind nachgezogen.

- **Aufräumen nach der Website-Überarbeitung (Phase 4).** `inline-migration.css` ist entfernt, die
  letzten `krtm-*`-Klassen sind Design-System-Bausteine oder Seitenregeln; Bank-Administration hat
  Seitenkopf und Hinweis-Boxen. Tote CSS-Regeln, Skripte und 205 Sprachschlüssel sind weg; das
  Bank-Kontodetail ist auf großen Bildschirmen so breit wie die übrigen Seiten (REQ-UI-027).

- **Kartellbank: „Bestätigen" nur, wo du bestätigen darfst.** Die Antragswarteschlange zeigt die
  Bestätigen-Aktion nur noch bei Anträgen, für die dir die passende Konto-Berechtigung zusteht; sonst
  steht dort „wartet auf berechtigte Bankmitarbeiter". Die Queue-API liefert dafür pro Antrag
  `callerMayConfirm` (REQ-BANK-023).

- **Blueprints: Fehlmenge wieder sichtbar.** Ist der Vorrat einer Zutat zu knapp, steht unter dem
  Vorrat wieder „fehlt n“ (REQ-INV-048).

- **Auftragsdetail: Reiter „Hersteller“.** Der Reiter, der zeigt, wer ein Item craften kann, hieß
  „Herstellung“ wie das Buchen der Herstellung im Reiter „Items“; er heißt jetzt „Hersteller“
  (REQ-ORDERS-026).

- **Build: Configuration-Cache auch für lokale Builds.** `gradle.properties` schaltet ihn ein;
  `--no-configuration-cache` schaltet ihn für einen Lauf ab (BLD-PERF-04).

- **CI: Cache-Janitor behält je Gradle-Job nur den neuesten Cache** und warnt, wenn der Actions-Cache
  nach dem Aufräumen über 8 GiB liegt (CI-01).

- **Tests: PostgreSQL-Testcontainer per Digest.** Die Backend-Tests starten genau das Produktions-Image
  (`TestImages.POSTGRES`, gegen Compose und Quadlet-Unit geprüft; TST-18).

- **CI: Dependabot-Digest-Bumps von Redis und PostgreSQL ziehen `TestImages` mit.** Der Compose-Follow-up
  setzt die Digest-Konstanten auf den Compose-Pin, statt `TestImagesTest` rot zu lassen (REQ-OPS-035,
  ADR-0215).

- **Doku: Verlängerung der OWASP-Suppressions beschrieben** (CONTRIBUTING, SEC-16).

- **Modularisierung: die Entscheidungen sind festgehalten.** ADR-0231 bis ADR-0239 (Modulschnitt,
  Zusammenspiel der Module, Prüfwerkzeuge, harter API-Schnitt mit erzwungenem App-Update, Fehlermodell,
  Zugriffsregeln je Fachbereich, NullAway, `switch` ohne `default`, Browser-Baseline 2025), dazu
  REQ-API-001/009/010 und REQ-FE-018. Checkstyle akzeptiert `_` als Namen eines leeren `catch`.

- **Architekturtests schlagen bei verschobenen Klassen an.** Die ArchUnit-Regeln des Backends wählen
  Klassen nach Rolle (`@RestController`, `@Service`, Repository, `@Mapper`, `@Entity`) und Klassenliteral
  statt nach Paket- und Namensstrings, jede Regel prüft eine Mindestauswahl, und ein Metatest prüft, dass
  jeder verbliebene Klassenname auflösbar ist (REQ-SEC-073). Eine Verschiebung lässt den Build scheitern,
  statt eine Regel still leerlaufen zu lassen.

- **API: mehr Bereiche werden nie zwischengespeichert.** Admin, Audit, Verbundene Anwendungen,
  Datenaustausch, Leitung, Live-Sync, Materialbörse, Benachrichtigungsregeln, Aufträge, Organigramm,
  SK-/Staffel-Mitglieder und der eigene Nutzungsbedingungs-Status antworten jetzt mit
  `private, no-store` statt `no-cache, must-revalidate` (REQ-SEC-031); ein Test schlägt fehl, wenn
  eine neue API-Familie nicht eingeordnet ist.

- **API: höchstens 1 000 Einträge pro Seite.** Nur Preismatrix, Preisübersicht, Materialpreise,
  Terminals und die UEX-Ortskataloge erlauben weiter 100 000 (REQ-API-005); Frontend und App fordern
  sonst nirgends größere Seiten an.

- **Datenaustausch: ein Refinery-Entwurf verlangt laut Schema mindestens einen Auftrag.**
  `refinery-draft.schema.json` setzt `minItems: 1` auf `orders`, wie das Backend es schon immer verlangt; eine
  leere Liste lehnt jetzt das Gateway selbst mit `400 SCHEMA_INVALID` und `errors[]` ab statt des weitergereichten
  Backend-Fehlers. Dasselbe gilt für `goods` und `sourceImages` jedes Auftrags, die ebenfalls mindestens
  einen Eintrag verlangen. Keine Vertragsänderung im Sinne von v2 (REQ-XCH-026).

- **Build: Qualitätsschwellen pro Modul ausdrücklich.** Testheap, JaCoCo-Schwellen und PIT-Ziele
  stehen in `<modul>/build-settings.properties`; fehlt ein Wert, bricht die Konfiguration ab, statt
  still auf 0.50/0.40 zu fallen (REQ-OPS-037). Die Werte sind unverändert.

- **Build: Prüfungen, die an Pfaden hängen, schlagen fehl statt still zu schrumpfen.** Neue Prüfung,
  dass jeder Eingabepfad der Build-Skripte existiert; i18n- und Seiten-CSS-Scans lesen Unterordner;
  ein Frontend-DTO ohne Backend-Gegenstück fällt durch; ein `ContextShapeTest` je Anwendung zählt
  geplante Jobs, Transaktions-Listener, Controller und Filterketten (REQ-OPS-038).

- **Tests: das Test-Profil setzt nur noch der Build.** 237 überflüssige `@ActiveProfiles("test")`
  sind entfernt; sie teilten Springs Test-Context-Cache, ohne etwas zu ändern. Ein Test je Anwendung
  verhindert, dass sie wiederkommen (REQ-OPS-039).

- **Tests: Testklassen teilen sich ihre Spring-Kontexte.** Das Backend startet 34 statt 46
  Anwendungskontexte, das Frontend 19 statt 21: ungenutzte Mocks sind entfernt, acht Klassen nutzen
  den gemeinsamen Mock-Satz `@LeafServiceMockTest`, Sicherheits-Beans bleiben echt. Ein Budget-Test
  je Anwendung verhindert, dass der Cache wieder zerfällt (REQ-OPS-041).

- **CI: Mutationstests auch für den Ingest-Gateway.** Der wöchentliche PIT-Lauf (`pitest.yml`) prüft
  jetzt auch `ingest`, mit eigenem Timeout und demselben Abschluss-Gate; mutiert werden alle
  Zuständigkeitspakete mit Gates und ihrem Zustand statt nur der früheren `service`-Klassen (MB-07).

- **Die Mindestversion der Android-App gehört jetzt zum Release** (REQ-API-020). Untergrenze, neueste
  Version und Release-Seite stehen fest in der `application.yml` des Backends (17 / 17) und gehen bei
  einem Rollback mit zurück. `APP_ANDROID_MINIMUM_VERSION_CODE`, `…_LATEST_VERSION_CODE` und
  `…_RELEASES_URL` werden nicht mehr gelesen; für den Notfall gibt es die leeren
  `APP_ANDROID_*_OVERRIDE`, die beim Start mit `WARN` und nach einem Tag als Alarm
  `AndroidVersionPolicyOverrideActive` auffallen.

- **Stillgelegte App-Endpunkte antworten `410 APP_UPDATE_REQUIRED`** statt mit einem Fehler, auch ohne
  gültiges Token, damit die App ihre Update-Sperre zeigt (REQ-API-020). Die Liste
  `api/retired-operations.txt` ist noch leer; bis dahin ändert sich nichts.

- **API-Vertrag: Die Liste der App-Aufrufe ist jetzt Teil des Vertrags.** Die eingefrorene Menge deckt
  jeden Aufruf der App ab (elf fehlende Operationen und sieben Query-Parameter ergänzt); ein Bruch gegenüber
  dem letzten Release muss in `declared-breaks.txt` stehen, und dieser Vergleich läuft in CI verpflichtend
  statt still übersprungen zu werden (REQ-API-016, REQ-API-017).

- **API-Dokumentation: `no-store`-Bereiche ohne ETag und `304`.** `openapi.json` beschreibt für die
  nie zwischengespeicherten API-Familien nur noch `Cache-Control: private, no-store`, wie das Backend
  antwortet; ETag und `304 Not Modified` stehen nur bei den revalidierbaren (REQ-SEC-031).

- **`openapi.json`: ein Tag pro Fachdomäne, Vertragsstufe pro Operation.** Jede Operation trägt genau
  ein Domänen-Tag (22 Domänen, `x-domain`) und ihre Stufe `x-contract-tier` (T0/T1/T2, Quelle
  `api/contract-tiers.txt`); die Change-Set-`Op`s von Blueprint und Schiff sind erstmals richtig
  dokumentiert, und der Generator prüft das Dokument vor dem Schreiben (REQ-API-018).

- **Fehlercodes: ein Register statt verstreuter Strings.** Alle 50 Problem-Codes des Backends stehen in
  `CoreProblemCode` und in `problem-codes.txt`; `openapi.json` dokumentiert `code` mit seinen Werten,
  `correlationId`, `fieldErrors` und 429 auf allen `/api/**`-Operationen (REQ-API-019).

- **API-Edge: Die öffentliche API lässt genau die eingefrorenen Operationen durch, nach Verb und Pfad.**
  Die Freigabe ist eine aus dem Vertrag generierte nginx-Map statt Pfad- und Präfixregeln; alles andere
  beantwortet die Edge mit 404 (auch die bisherigen 405). `GET /api/v1/me/layout`, `POST /api/v1/job-types`
  und Pfade unter `/api/v1/me/` und `/api/v1/terms/`, die die App nicht aufruft, sind dort nicht mehr
  erreichbar; die App selbst ändert sich nicht (REQ-API-021, REQ-OPS-042). `HEAD` wird auf dem
  öffentlichen API-Host nicht mehr durchgelassen.

- **Mehr API-Antworten sind `private, no-store`.** Jede `/api`-Familie, die nicht ausdrücklich als
  teilbar eingestuft ist, gilt als persönlich; darunter jetzt `/orders` (außer dem Artikelkatalog) und
  `/exchange` (außer dem Katalog). Diese GETs tragen kein ETag und kein `304` mehr (REQ-SEC-031).

### Fixed

- **Benachrichtigungs-Stream: kein ERROR mehr, wenn der Browser geht.** Schloss ein Browser den
  Live-Stream genau während eines Ereignisses, schrieb das Frontend einen `IllegalStateException`
  als ERROR ins Log. Ein abgebrochener Schreibvorgang beendet jetzt nur noch die Verbindung zum
  Backend (Frontend, REQ-NOTIF-010).

- **Lager: `canEdit` sagt die ganze Wahrheit.** Das Feld war für ein einfaches Mitglied auch auf den
  Zeilen anderer Mitglieder seiner Einheit `true`, obwohl jede Zeilenaktion dort mit 403 abgelehnt
  wird; die App bot deshalb Aktionen an, die scheiterten. Jetzt gilt: Besitzer oder Logistiker und
  höher, im eigenen Bereich (API, REQ-SEC-047).

- **Hinweis-Toasts schlucken keine Klicks mehr.** Ein eingeblendeter Erfolgs- oder Fehler-Toast
  (unten rechts) fing fünf Sekunden lang jeden Klick auf das ab, was er verdeckte – etwa den
  Löschen-Knopf einer gerade angelegten Materialkategorie. Toasts sind jetzt für die Maus
  durchlässig (styles.css, REQ-UI-008).

- **Admin-Seiten Missionsdaten und Spezialkommandos: Meldungen ohne Anführungszeichen.** Toasts
  und Dialogtitel zeigten ihren Text in `"…"`, weil das Inline-Skript die Übersetzung in ein
  JavaScript-Stringliteral schrieb; die Werte kommen jetzt sauber aus dem Seiten-Bootstrap
  (Templates, ADR-0069).

- **Auftragsdetail und Einsatzdetail: Fehlermeldungen in der Sprache des Nutzers.** Der Ladefehler der
  Lagereinträge eines Auftrags und fünf Ersatztexte der Einsatzseite waren fest auf Deutsch; sie
  kommen jetzt aus den Sprachdateien, ein Test findet fest verdrahteten Text in Skripten (i18n).

- **Request-Metriken fallen nicht mehr nach 100 Endpunkten weg.** Backend und Frontend durften nur
  100 verschiedene `uri`-Werte in `http.server.requests` / `http.client.requests` führen; jeder
  danach erstmals aufgerufene Endpunkt fehlte in 5xx- und Latenz-Alerts und Dashboards. Die Grenze
  liegt jetzt bei 1000, ein Test hält sie über der Zahl der Routen, und `HttpUriTagCapNear` warnt
  vor dem Erreichen (config, REQ-OBS-006).

- **Herstellung: Mengen mit vierter Nachkommastelle werden nicht mehr abgelehnt.** Der Dialog rundet
  jede Verbrauchsmenge auf die Genauigkeit des Materials, bevor er prüft und sendet; vorher ließ er
  sie durch und das Backend antwortete mit „Materialzuweisung deckt den Bedarf nicht exakt"
  (REQ-ORDERS-025).

- **Umbuchen ohne neues Ziel wird im Dialog abgefangen.** Bleiben Nutzer und Ort wie vorbelegt,
  meldet der Dialog das auf Deutsch bzw. Englisch, statt die Anfrage zu senden und den englischen
  Backend-Text zu zeigen; das Backend liefert den Fehler jetzt ebenfalls lokalisiert (REQ-INV-025).

- **Startseite und Buchungsanträge passen wieder auf Smartphone und Tablet.** Auf der Startseite
  brechen Status, „Angemeldet" und „Meine Einheit" am Smartphone in eine zweite Zeile um, statt die
  Seite zu verbreitern; die Antragsliste der Kartellbank stapelt am Tablet die Entscheidungsknöpfe,
  statt seitlich zu scrollen (CSS, REQ-UI-009).

- **Blueprints: Seite scrollt nicht mehr seitlich.** Die unsichtbare Hinweisblase neben
  „Raffinerie einrechnen" verbreiterte die Seite am Smartphone auf 487 px und am Tablet um 104 px;
  verborgene Hinweisblasen belegen jetzt keinen Platz mehr (CSS, REQ-UI-009).

- **Systemeinstellungen: Auftragsbearbeitung pro Spezialkommando wieder schaltbar.** Der Abschnitt
  zeigte seit dem Wegfall des anonymen Auftragsformulars immer „Keine Spezialkommandos gefunden.";
  jetzt listet er alle aktiven SKs mit ihrem Schalter (Frontend-Controller, REQ-ADMIN-001).

- **Datenauskunft: Qualität der Eintragungen.** Der Export zeigt die Qualitätsstufe einer
  Material-Eintragung wieder an; seit dem Stufen-Katalog blieb sie bei neuen Eintragungen leer.

- **Sandbox: Seed läuft wieder.** Die Beispielaufträge des Exchange-Sandbox-Seeds nennen ihre
  Qualitätsstufe; seit V262/V263 brach der Seed an der fehlenden Stufe ab.

- **Aufträge: Übergaben buchen auf die richtige Zeile.** Eine Übergabe verringerte bisher immer die
  erste Zeile eines Materials und löste alle Zuordnungen, sobald diese erfüllt war — auch wenn eine
  zweite Qualitätsstufe noch offen war. Bestand unter der Mindestqualität wird jetzt abgelehnt; die
  Herstellung prüft die Qualität ebenfalls (REQ-ORDERS-038/039).

- **Deploy: kein Fehlalarm mehr während einer Promotion.** Trifft ein Deploy-Tick das kurze Fenster, in dem
  `promote.yml` die fünf `:stable`-Tags nacheinander verschiebt, wartet `deploy.sh` jetzt ab, statt
  `DeployFailed` auszulösen. Erst ein Satz, der länger als `IRI_MIXED_RELEASE_GRACE` (900 s) gemischt bleibt, gilt
  als Fehler.

- **Aufträge: Bestand wird nicht mehr doppelt gezählt, wenn ein Material in mehreren Qualitäten
  gebraucht wird.** Verknüpfter Bestand wird genau einer Stufe angerechnet (höchste Stufe zuerst);
  Auftragsdetail, Materialbedarf, Lager-Auswahl und Exchange-Feed zeigen den echten offenen Bedarf
  (REQ-ORDERS-037).

- **Qualität ist überall auf 0–1000 begrenzt.** Die Datenbank prüft es, Eingaben darüber werden
  abgelehnt, importierte Blueprint-Werte werden gekappt (REQ-DATA-023, V265).

- **Crew-Board auf dem Smartphone: Ziehen unter die Tab-Leiste.** Beim Ziehen eines Teilnehmers scrollt die
  Seite jetzt schon oberhalb der Tab-Leiste, sodass eine verdeckte Einheit erreichbar wird; Loslassen über
  der Tab-Leiste bricht ab, statt die Zuweisung zu entfernen.

- **Org-Einheiten-Auswahl im Menü auf Tablets wieder 44 px hoch** (Touch-Mindestgröße, REQ-UI-009).

- **E2E: nach einem Formular-Submit wartet die Suite, bis die Zielseite geladen ist.** Die nächste
  Navigation überholte sonst die noch ladende Seite; in WebKit verlor der Browser dabei vereinzelt alle
  Cookies, und `JobOrderProductionE2eTest` landete abgemeldet auf `/?error`. Kein Fehler der App.

- **Build: die Node-Skripte unter `frontend/scripts/` werden gelintet und auf Formatierung geprüft.**
  `:frontend:lintJs` und `:frontend:prettierCheck` lasen `scripts/**/*.mjs` entgegen ADR-0130 nicht; jetzt
  tun sie es, und die fünf Skripte sind ohne Verhaltensänderung nach Prettier formatiert. ADR-0222 nennt
  die zwei lokalen Änderungen an `google_checks.xml`, statt es „verbatim" zu nennen.

- **Datenexport (Art. 15/20 DSGVO) vollständig.** Der Export enthält jetzt auch deine zugewiesenen
  Rollen, eine Ernennung zum Grand Admiral, Einsätze mit dir als Partyleiter, Einsatzeinheiten in
  deiner Verantwortung, Interessensbekundungen zu Gesuchen der Materialbörse, Buchungsberechtigungen
  und persönliche Freigabe-Limits auf Bankkonten sowie Buchungsanträge mit dir als Gegenpartei; andere
  Personen bleiben dabei ausgeblendet. Im PDF tragen außerdem die vier Abschnitte der verbundenen
  Anwendungen jetzt einen lesbaren Namen statt ihres internen Schlüssels.

- **Admin: Bearbeiten und Löschen im persönlichen Inventar eines Mitglieds funktionieren wieder.** Das
  Frontend rief `/api/v1/admin/personal-inventory/{id}` statt `…/items/{id}` auf, das Backend lehnte ab.
  Ein neuer Test prüft jetzt jeden Backend-Aufruf des Frontends gegen die `openapi.json` (REQ-FE-028).

- **Parallel geladene Seitenabschnitte senden die Sprache des Nutzers ans Backend.** Einsätze, Operationen,
  Aufträge, Lager, Hangar, Raffinerie, Org-Bank und Einsatzdaten luden Abschnitte ohne `Accept-Language`; der
  `ParallelPageLoader` übernimmt jetzt den ganzen Anfragekontext aus dem `ContextRegistry` (REQ-FE-030).

- **App: Auftraggeber können ihren Auftrag wieder bearbeiten.** Die öffentliche API-Edge ließ
  `PUT /api/v1/orders/{id}/requested` nicht durch, die Bearbeitung in der App endete seit v0.2.0 mit 404.
  Nach dem Deploy ist genau dieser `PUT` freigegeben (andere Methoden bleiben 404).

- **API-Fehler: richtige Status, Codes und Header statt 500 oder leerem Body.** Fehlende Parameter,
  Header, Cookies oder Multipart-Teile antworten `400 BAD_REQUEST`, ein nicht lieferbarer Medientyp
  `406 NOT_ACCEPTABLE` (neuer Code), ein zu großer Upload `413 REQUEST_BODY_TOO_LARGE` statt `500`; jeder
  429 trägt `code = RATE_LIMIT_EXCEEDED`, `Retry-After` und eine Correlation-Id, auch der des
  Pro-Konto-Limits und `POST /api/v1/live-sync/changed` (bisher ohne Body). Vier Bank-Codes zeigen jetzt
  einen übersetzten Titel statt des Schlüssels.

- **Datenschutzerklärung korrigiert: der Sitzungs-Cookie ist `SameSite=Lax`.** Konfiguriert war
  `Strict`, gesetzt wurde aber immer `Lax`; `Lax` bleibt bewusst, weil Anmeldung über Discord und
  Links aus anderen Seiten den Cookie brauchen. Das Frontend setzt jetzt jedes konfigurierte
  Cookie-Attribut tatsächlich, ein Test hält den gesendeten `Set-Cookie` fest.

### Security

- **Frontend-Lint-Werkzeug: `source-map-js` 1.2.2.** Behebt GHSA-68fv-2mgg-jv7q (Denial of Service über
  indizierte Source-Map-Abschnitte) in der Abhängigkeit von Stylelint/PostCSS; läuft nur im Build,
  nicht in der App.

- **Frontend: Trusted Types im Report-Modus.** Jede Seite sendet zusätzlich den Header
  `Content-Security-Policy-Report-Only: require-trusted-types-for 'script'; trusted-types krt-html
  krt-fragment`; ein ungeschützter DOM-Schreibzugriff wird als `csp_violation` gemeldet, nichts wird
  blockiert. Neue Variable `APP_SECURITY_TRUSTED_TYPES` (`report` als Vorgabe, `enforce` erzwingt);
  das Umschalten der Produktion ist ein eigener, freizugebender Schritt (REQ-SEC-064, ADR-0239).

- **Lager: „gestohlen"-Markierung und Zuordnungen fremder Einträge nur noch für Logistiker.** Wie
  Ausbuchen, Notiz und Geliefert verweigern jetzt auch `POST /inventory/{id}/stolen` und
  `POST`/`PATCH`/`DELETE /inventory/{id}/allocation` einem Mitglied unterhalb von Logistiker den
  Eintrag eines anderen Mitglieds mit `403`; bisher reichte die Einheit (REQ-INV-053, REQ-INV-027).

- **Frontend: die Session liest nur noch die 21 Anwendungstypen, die wirklich dort landen.** Statt
  aller 325 Klassen unter `frontend.model` nennt die Session-Allow-List die aus dem Code abgeleiteten
  Formulare und DTOs einzeln; ein neuer Session-Typ ohne Eintrag bricht den Build. Geht als eigenes
  Release vor dem Paket-Umbau des Frontends live (D-10); niemand wird abgemeldet, Konfiguration
  unverändert (REQ-SEC-067, REQ-FE-027).

- **Monitoring: Grafana 13.2.2 → 13.2.3.** Sicherheitsrelease gegen CVE-2026-13719,
  CVE-2026-13720 und CVE-2026-81841 (je mittlere Schwere: Alert-Regeln fremder Ordner lesbar,
  Provisioning-Metadaten durch Editoren setzbar, pausierter öffentlicher Dashboard-Link blieb
  gültig). Digest in Compose und Quadlet aktualisiert; wirkt nach dem nächsten Deploy.

- **Basis-Images auf den aktuellen Stand der Tags gehoben (nur Digests, keine neuen Versionen).**
  
  Temurin JRE/JDK 25.0.4.1+1 (sicherheitsbedingter Sonderrelease), `nginx-unprivileged:1.31.6-alpine`
  mit neuen Alpine-Schichten und der BuildKit-Builder `buildx-stable-1`. Die Log-Signaturen von
  `JvmNativeThreadExhaustion` und `JvmStartupCacheRejected` wurden auf dem neuen JRE erneut geprüft.

- **Ingest prüft den Hostnamen des Backends standardmäßig.** Der Jar-Default von
  `app.ingest.verify-backend-hostname` ist jetzt `true`; nur das `dev`-Profil schaltet ihn ab.
  Compose und Quadlet setzten bereits `true` (ING-SEC-04).

- **Host: `deploy` darf nur noch die podman-Unterbefehle der Deploy-Skripte als `iri` ausführen**
  statt `podman *`; repo-lint prüft die Liste gegen die Skripte. Wirkt erst nach einem Ansible-Lauf
  auf dem Host (OPS-SEC-08).

- **CI: Werkzeuge per Hash gepinnt.** PyYAML, ansible-core/ansible-lint aus `--require-hashes`-Dateien,
  markdownlint-cli2 aus einem Lockfile, Ansible-Collections exakt versioniert (CI-SEC-12).

- **CI: Release-App-Schlüssel in der Umgebung `release`.** `release-prepare`, `refresh-versions` und
  `release-publish` lesen ihn dort; `release-publish` läuft dafür auf dem Push nach `main` und erkennt
  den Release-Merge selbst. Der Owner legt die Umgebung einmalig an und löscht danach das
  Repository-Secret (CI-SEC-16, ADR-0201).

- **Backend: Prüfregeln für Mandantentrennung und Massenzuweisung.** Org-Einheiten-gebundene Aggregate
  tragen `@TenantScoped`; ein Test verlangt für jeden Controller, der solche Daten schreibt, den
  Scope-Gate, und für Request-Bodies `@Valid`, keine Antwort-DTOs und keine servergeführten Felder
  (REQ-ORG-028, REQ-SEC-077, REQ-API-015). 13 Admin-/SPI-Endpunkte validieren ihren Body jetzt;
  gültige Anfragen ändern sich nicht.

- **Pfadgebundene Schutzregeln prüfen sich selbst.** Tests über die echten Endpunkte stellen sicher,
  dass jeder schreibende Endpunkt in der CSRF-Ausnahme liegt, jede Ratenlimit-Regel einen Endpunkt
  trifft und die Ausnahmen der Freigabe-, Nutzungsbedingungs- und Datenaustausch-Filter exakte
  Pfadlisten sind (REQ-SEC-078…080).

- **Die Backend-Clients des Frontends senden nur noch an das Backend.** Jeder `WebClient` lehnt eine Anfrage an
  einen anderen Host als `app.backend-url` ab, bevor das Bearer-Token angehängt wird; ArchUnit hält die Clients im
  Backend-Kern (REQ-FE-029).

- **Build: Jackson-3.2.1-Fehlalarm aus dem Abhängigkeitsgraphen entfernt.** Die Launcher-Suche des
  PIT-Plugins löste die Testabhängigkeiten ohne Spring-Boot-BOM auf und meldete so Jackson 3.2.1
  (Dependabot #40–#46); sie ist abgeschaltet. Ausgeliefert wird weiter das gepatchte Jackson 3.1.7 bzw. 2.21.7.

## [v1.13.7](https://github.com/krt-profit/basetool/releases/tag/v1.13.7) - 2026-10-03

### Added

- **Schnellzugriff (Strg K / ⌘ K).** Ein Suchfeld in der Kopfzeile findet jede Seite, die man sehen darf,
  dazu „Zuletzt besucht" und Aktionen wie „Neuen Auftrag anlegen" oder „Abmelden"; bedienbar per Tastatur.

- **Tab-Leiste auf dem Smartphone.** Einsätze, Lager, Aufträge, Meldungen und „Menü" sitzen unten fest; das
  Menü öffnet als Sheet, die Suche als Vollbild. Die Fußzeilen-Links stehen dort am Ende des Menüs.

### Changed

- **Neues Navigationsmenü.** Das Menü hat einen Menüfilter, Gruppen mit Icons und unten eine Nutzerzeile mit
  „Persönlich", Sprache und Abmelden. „Administration" ist ein eigener Modus (auf Admin-Seiten automatisch),
  „Rechtliches" steht nur noch in der Fußzeile, die Glocke sitzt in der Kopfzeile.

- **Lager einbuchen: „Mit vorhandenem Bestand zusammenführen" erscheint nur noch, wenn es etwas zum
  Zusammenführen gibt.** Das Formular fragt beim Backend (`GET /api/v1/inventory/merge-candidates`), ob
  dasselbe Material in derselben Qualität am selben Ort mit demselben Besitzer und derselben Einheit schon
  liegt; sonst bleibt die Zeile weg. Der Hilfetext nennt die Merge-Kriterien jetzt richtig.

- **„Zuordnen zu" wählt die aktive Org-Einheit vor.** Ist die Einheit aus der Sidebar unter den Optionen,
  steht sie schon ausgewählt (Lager, Hangar, Einsatz, Operation, Raffinerie); sonst heißt die leere
  Auswahl „Bitte wählen" statt des irreführenden „Heimat-Staffel beibehalten".

- **Einbuchen für ein anderes Mitglied: „Zuordnen zu" zeigt dessen Einheiten.** Wer im Globalen Lager
  einen Nutzer wählt, bekommt dessen Org-Einheiten angeboten statt der eigenen.

- **Einbuchen: mehrere Zuordnungen brauchen jede eine Menge.** Eine Auftrags- oder Einsatzzeile ohne Menge
  wurde bisher stillschweigend verworfen; jetzt markiert das Formular sie und bucht erst, wenn sie ausgefüllt
  ist.

### Fixed

- **E2E: nach einem Formular-Submit wartet die Suite, bis die Zielseite geladen ist.** Die nächste
  Navigation überholte sonst die noch ladende Seite; in WebKit verlor der Browser dabei vereinzelt alle
  Cookies, und `JobOrderProductionE2eTest` landete abgemeldet auf `/?error`. Kein Fehler der App.

- **Deploy: die Edge startet vor den Anwendungen.** Ändert ein Release die Edge-Unit, stoppte das eine
  Neustartfenster sie mit und startete sie zuletzt; Backend und Frontend holen die OIDC-Konfiguration
  aber über den öffentlichen Namen, also über die Edge, und kamen nicht hoch. Gilt auch für `backup.sh`.

- **Host-Rolle: Drop-in-Verzeichnisse werden angelegt.** Die Rolle schrieb den Host-Alias für
  `grafana` und `blackbox-exporter` in Verzeichnisse, die es auf einem Host noch nicht gab, und brach ab.

- **Einsatzdaten: „Inaktive anzeigen" bei den Aufgaben lädt die Seite nicht mehr neu.** `sidebar.js`
  schickte das Filterformular zusätzlich zum In-Place-Tausch ab und lud so die ganze Seite; der
  Filter tauscht jetzt nur noch seinen Abschnitt aus, wie bei Staffeln und Frequenztypen.

- **Keine Warnung vor ungespeicherten Änderungen mehr nach einem Filter.** Wer einen Filter oder eine
  Suche umstellt (z. B. „Nutzungsbedingungen", Hangar, Kontoverlauf, Blueprints) und dann blättert oder
  die Seite verlässt, wird nicht mehr gewarnt. Der Filter der Nutzungsbedingungen hat keinen
  „Anwenden"-Button mehr, er greift sofort.

## [v1.13.6](https://github.com/krt-profit/basetool/releases/tag/v1.13.6) - 2026-10-02

### Added

- **Alarm `BackupRetentionNotApplied`.** `backup.sh` meldet die Zahl der Snapshots im Repository und das
  Maximum der Aufbewahrung (`basetool_backup_snapshots`, `basetool_backup_retention_limit`); liegt die Zahl
  darüber, warnt der Alarm.

### Changed

- **Datenaustausch: Lagerposten umfassen das ganze eigene Lager.** Verbundene Anwendungen sehen und
  setzen jetzt alle Lagerzeilen des Mitglieds, persönliche und geteilte, wie „Mein Lager" sie zeigt;
  Ausbuchungen nehmen zuerst persönliche Zeilen und nie reservierten Bestand. `materialKind` liefert
  zusätzlich die UEX-Merkmale `mineral`, `harvestable`, `raw`, `refined`, `buyable`, `sellable`
  (Migration `V260`, ADR-0230). Die Datenschutzerklärung (Abschnitt 3.10) nennt das jetzt so.

### Fixed

- **Backup: die Aufbewahrung greift wieder.** `restic forget` gruppierte nach dem Pfad, und jeder Lauf sichert
  aus einem eigenen Staging-Verzeichnis, also wurde nie ein Snapshot entfernt (109 statt höchstens 17). Jetzt
  gruppiert es nach Host und Tag.

- **Backup: kein `restic init` mehr auf einen Fehler hin.** Initialisiert wird nur ein Repository, das nicht
  existiert; ein 403 des Speichers, ein falsches Passwort oder ein Lock brechen mit restics eigener Meldung im
  Log ab. Veraltete Locks räumt der Lauf vor dem Upload ab.

- **Ansible-Rolle installiert `lib/restic-repo.sh`.** Ohne die Datei bricht das neue `backup.sh` auf dem Host
  beim Start ab; ein Selbsttest prüft jetzt, dass jede eingebundene Bibliothek mitinstalliert wird.

- **Datenaustausch: Rohstoffe behalten ihre Qualität.** Die Regel „Handelsware auf Qualität 0" traf
  jedes Material aus dem UEX-Katalog, auch alle Erze und Metalle; jetzt wird jedes Material mit der
  gesendeten Qualität gebucht. Bereits mit Qualität 0 gebuchte Posten bleiben, bis das Mitglied oder
  die Anwendung sie korrigiert.

## [v1.13.5](https://github.com/krt-profit/basetool/releases/tag/v1.13.5) - 2026-10-01

### Added

- **Betriebsmodus der Session-Typprüfung als Metrik.** Das Frontend meldet den wirksamen Modus
  (`off`/`report`/`enforce`) als `basetool_session_type_allow_list_mode`; so ist nach einem Rollout
  sichtbar, dass `enforce` im Prozess angekommen ist.

### Changed

- **Deploy: nur ein Release auf einmal.** Die Promotion prüft alle fünf Artefakte und ihren gemeinsamen Quell-Commit, bevor sie `:stable` verschiebt; `deploy.sh` verweigert einen Satz aus verschiedenen Commits und versucht es im nächsten Tick erneut. Notausgang: `IRI_REQUIRE_ONE_RELEASE=false`.

- **Keycloak 26.8.0.** Neues Image `quay.io/keycloak/keycloak:26.8`; Provider-JAR, Theme und Realm laufen
  unverändert. Brute-Force-Sperren bleiben jetzt über einen Neustart erhalten (Login-Fehler liegen in der
  Datenbank). Die Mindestversionen für Netty, OpenTelemetry und Jackson im SPI-Build entfallen,
  Keycloak bringt selbst neuere mit (Protobuf bleibt angehoben). Der Tag-Wechsel ist betreibergesteuert: vorher frisches Backup, ein
  Zurückrollen heißt Datenbank wiederherstellen; laufende Anmeldungen müssen einmal neu beginnen.

- **Keycloak-Log ohne Daueralarm.** Die „Full scope allowed"-Warnung (je Token-Anfrage) und der veraltete
  Sticky-Session-Schalter sind abgestellt.

- **Keycloak: Fehler-Events stehen im Log auf INFO statt WARN.** Ein Discord-Callback ohne Login-Cookie (`cookie_not_found`) ist ein Client-Zustand und verstopfte die WARN-Ansicht; die Zeilen und die Event-Metriken bleiben erhalten (`KC_SPI_EVENTS_LISTENER__JBOSS_LOGGING__ERROR_LEVEL`).

- **Keycloak-Infoseite: Buttons einheitlich.** „Zum Basetool" und „Tab schließen" haben jetzt gleiche Breite, Höhe und Rahmen, mit Abstand zum Text darüber.

### Fixed

- **Backend-Fehler auf Seiten und Downloads tragen den richtigen HTTP-Status.** Die Fehlerseite für eine
  Backend-Ablehnung kam bei Navigation oder einfachem `fetch` mit Status 200 statt 404/403/409/…; ein Download, der
  `response.ok` prüft (Übergabeprotokoll), hätte die Fehlerseite als Dokument gespeichert. AJAX-Antworten
  (JSON) waren schon korrekt.

- **Android: Operation anlegen funktioniert.** Die API-Domain lässt jetzt genau `POST /api/v1/operations` durch
  (zuvor 404); `GET`, `PUT`, `DELETE` und ähnliche Pfade bleiben gesperrt. Der Aufruf ist Teil des eingefrorenen
  App-Vertrags.

- **Downloads, Importe und „Alles löschen" bei abgelaufener Anmeldung.** Elf Proxy-Controller (PDF-/Datenexporte,
  P4K-, Hangar- und Blueprint-Import, Hangar-/Lager-„Alles löschen") umgingen die gemeinsame Fehlerbehandlung:
  eine abgelaufene Sitzung führte zu einem Fehler 500 statt zur erneuten Anmeldung, und die Fehler wurden nicht
  gezählt. Sie laufen jetzt über `BackendApiClient`; ein nicht erreichbares Backend meldet 504 statt 500.

- **Raffinerieauftrag: Einlagern nur einmal.** Ein bereits eingelagerter Auftrag lässt sich weder per Bearbeiten wieder öffnen noch ein zweites Mal einlagern (keine doppelten Lagerzeilen und Audit-Einträge). Beim Einlagern wird außerdem geprüft, dass der gewählte Auftrag das Material benötigt (Migration V259, REQ-REFINERY-022). Neue Aufträge entstehen immer als „Offen“; ein mitgeschickter Status wird ignoriert, das Erstellformular bietet keine Statuswahl mehr.

- **Materialbedarf: Suchkopf der Materialauswahl ist wieder deckend schwarz.** Zwei Farbwerte der Oberfläche
  (Materialbedarf-Suche, Kontogruppen-Titel der Bank) verwiesen auf nicht existierende Design-Tokens; ein Test
  prüft künftig jedes `var(--…)`.

- **Mitgliederliste: Dialogtexte werden als JavaScript-Text ausgegeben.** Löschen-, Sync- und
  Zusammenführen-Dialog bekamen ihre Übersetzungen HTML-maskiert; ein Anführungszeichen in einer
  Übersetzung hätte `&quot;` angezeigt.

- **Mein Inventar: Sortierung mit Richtung erreicht das Backend unverfälscht.** Die Sortierangabe wurde doppelt
  kodiert und nie ausgewertet; sie läuft jetzt über `RelayParams` und eine URI-Variable.

### Security

- **Session-Typ-Allowlist: `enforce` ist jetzt überall die Vorgabe.** Code, `application.yml`, Compose und
  Quadlet-Vorlage standen noch auf `report`, Produktion setzte `enforce` per `.env`. `report` ist nur noch
  ausdrücklich wählbar; ein fehlender oder falsch geschriebener Wert fällt auf `enforce` zurück.

- **Mutation-Testing-Lauf prüft jetzt auf Vollständigkeit.** Ein beim Job-Timeout abgebrochener Backend-Lauf
  galt mit seinem Teilbericht als grün; jetzt scheitert der Lauf bei abgebrochenem PIT-Schritt oder fehlendem
  `</mutations>`. Timeout 60 → 120 Minuten (Backend kam zweimal in Folge nicht in 60 durch).

- **Neuer Alarm `ContainerStartTimeMissing`.** Der Crash-Loop-Alarm liest die Startzeiten des Podman-Exporters
  und wurde still blind, wenn dieser keine Container-Serien mehr lieferte; der neue Alarm meldet das. Der Text
  von `ContainerMetricsMissing` nennt den Crash-Loop-Alarm nicht mehr.

- **Keine Namens-Hashes mehr im Log.** Frontend-Logzeilen zur Rollenprüfung und zum Rollenabgleich tragen kein aus dem Benutzernamen abgeleitetes Kürzel mehr.

- **Zugangsdaten verschwinden aus `toString()` und Logs.** Das Scrape-Passwort (Frontend, Ingest) und das Discord-Token im Keycloak-SPI werden nicht mehr ausgegeben; der Log-Maskierer erfasst zusätzlich `password=` und `secret=`.

- **CSV-Export der Beförderungsmatrix neutralisiert Formeln.** Zellen, die mit `=`, `+`, `-`, `@`, Tab oder Zeilenumbruch beginnen, erhalten ein führendes `'`.

- **Browser-IDs werden vor dem Backend-Aufruf geprüft.** Ankündigungs-ID, Mitglieds-ID des Beförderungsfragments, Standard-Blueprint-ID und der Rollen-Code der Bankkonten-Sichtbarkeit und -Limits müssen die erwartete Form haben, sonst antwortet das Frontend mit 400, ohne das Backend zu rufen.

- **Keycloak-Verbindung von Backend und Ingest mit Zeitlimits.** Der an das Zertifikat gepinnte Client
  (Admin-API und JWKS-Abruf) wartete unbegrenzt auf Keycloak; er nutzt jetzt 5 s Verbindungs- und
  30 s (Ingest 10 s) Lese-Timeout.

- **Lager- und Hangar-Rollenprüfung zusätzlich an den Endpunkten.** 13 Lager- und 2 Hangar-Endpunkte
  waren nur über die URL-Regel geschützt; dieselbe Rollenprüfung steht jetzt auch an der Methode, die
  URL-Regel bleibt. Wer Zugriff hat, ändert sich nicht.

- **Suchfelder finden `%` und `_` wörtlich.** In Standorten, Materialien, Nutzern, Missionen,
  Operationen, Schiffen (Hangar und Staffel-Übersicht), Materialbörse und Lager-Auswahl fand ein
  maskiertes Suchzeichen nichts mehr; die Abfragen setzen jetzt `ESCAPE`. Die Staffel-Hangar-Übersicht
  maskierte die Eingabe bisher gar nicht.

- **Katalogsuchen behandeln `%` und `_` wörtlich.** Die Produktsuche der Blaupausen sowie die
  Artikelauswahl für Aufträge und Lager übergaben Eingaben ungeschützt an `LIKE`; die Blaupausen-Abfragen
  setzen zusätzlich `ESCAPE`, ohne das Hibernate das Escape-Zeichen abschaltet.

- **Log-Level zur Laufzeit wieder per Bearer-Token änderbar.** `POST /actuator/loggers/**` scheiterte
  in Produktion am CSRF-Schutz mit 403; der Pfad ist jetzt wie `/api/v1/**` ausgenommen.

- **Live-Sync-Abo schlägt bei unklarem Ergebnis fehl, statt zu öffnen.** Bei 401, 5xx, Timeout, fehlendem Token
  oder voller Prüfwarteschlange wird ein Abo nun abgelehnt; der Tab abonniert bis zu fünfmal mit wachsender
  Wartezeit neu. Sockets mit abgelaufenem oder abgelehntem Token werden geschlossen und verbinden sich
  mit frischem Token neu.

- **Backup und Restore-Drill brechen ohne Digest-Pin ab.** Der Helfer-Container, der Secrets, `.env` und
  Zertifikate liest, fällt nicht mehr auf ein ungepinntes PostgreSQL-Image zurück; ohne lesbaren
  `@sha256:`-Pin endet der Lauf mit `FATAL`, `BackupStaleOrMissing` greift weiter.

- **Keycloak 26.8.0 schließt Sicherheitslücken.** CVE-2026-12388 (Rollen-Eskalation über
  Identity-Provider-Mapper), CVE-2026-14781, CVE-2026-19608 und CVE-2026-93999 (deaktivierte Clients im
  Token), dazu den Geräte-Login für brute-force-gesperrte Konten sowie Jackson und Netty im
  Keycloak-Image.

- **Jackson auf 2.21.7 und 3.1.7 angehoben** (Gradle-Plugins 2.22.3). Schließt CVE-2026-91776 und
  CVE-2026-91777 (beide HIGH) in `jackson-databind` für Backend, Frontend, Ingest, Keycloak-SPI und
  `logging-support`.

- **Jackson auch in Keycloak-SPI, `logging-support` und den Gradle-Plugins angehoben.** Dort lagen
  noch `jackson-databind` 2.21.5, 3.1.5 und 2.22.1 (CVE-2026-68497, CVE-2026-19032,
  CVE-2026-83557), jetzt 2.21.6, 3.1.6 und 2.22.2. Die Images von Backend, Frontend und Ingest
  waren seit v1.13.4 nicht betroffen.

## [v1.13.4](https://github.com/krt-profit/basetool/releases/tag/v1.13.4) - 2026-09-29

### Security

- **Jackson auf 2.21.6 und 3.1.6 angehoben.** Schließt CVE-2026-68497 (HIGH) in `jackson-databind`
  für Backend, Frontend und Ingest.

## [v1.13.3](https://github.com/krt-profit/basetool/releases/tag/v1.13.3) - 2026-09-29

### Changed

- **Build-Warnungen bereinigt.** Keycloak-SPI, Größenlimit-Filter und mehrere Tests nutzen keine veralteten oder ungeprüften Aufrufe mehr.

- **Star-Citizen-Links: Das Fandom-Wiki entfällt.** Es ist stark veraltet (REQ-UI-025).

## [v1.13.2](https://github.com/krt-profit/basetool/releases/tag/v1.13.2) - 2026-09-29

### Changed

- **Star-Citizen-Links: 20 neue Seiten und die Sektion „Offizielles".** Zwölf Seiten von Roberts Space
  Industries (u. a. Roadmap, Status, Ship Matrix, Starmap, Galactapedia) bilden die neue Sektion;
  dazu kommen SC Trading, der Holoviewer, SystemSeven, drei Wikis, SCDL und SC-Links.org
  (REQ-UI-025).

- **Star-Citizen-Links: VerseKit zeigt auf die neue Adresse.** Die Karte führt jetzt zu
  versekit.xharig.com (REQ-UI-025).

- **Keycloak-Theme: Die Seite nach einer Geräte-Anmeldung führt weiter.** Statt nur „Sie können
  dieses Browser-Fenster schließen" bietet sie „Zum Basetool" und „Tab schließen"; lässt der Browser
  das Schließen nicht zu, erklärt sie, wie es von Hand geht (REQ-XCH-005).

## [v1.13.1](https://github.com/krt-profit/basetool/releases/tag/v1.13.1) - 2026-09-28

### Changed

- **Keycloak: Offline-Sitzungen nur noch für die Austausch-Clients.** Der Realm-Provisioner entzieht
  `basetool-frontend`, `backend-service`, `basetool-ingest-gateway` und `grafana` den optionalen
  Scope `offline_access` und nimmt ihn aus den Standard-Client-Scopes des Realms; nur der
  SC Extractor und zugelassene Drittanwendungen behalten ihn (ADR-0202 Amendment 5, REQ-OPS-033).
  Für Mitglieder ändert sich nichts.

- **Datenaustausch: VerseKit ist zugelassen.** `docs/legal/approved-clients.md` führt VerseKit ab
  Version 3.60.0 mit acht Berechtigungen (#2272, #2273); Mitglieder können es unter
  „Verbundene Anwendungen" verbinden.

- **Backend: Handeln für ein Mitglied nur noch über die Austausch-Routen.** Das Ingest-Gateway darf
  `X-Ingest-On-Behalf-Of` nur noch auf `/api/v1/exchange/**` setzen; die früheren Import-Endpunkte
  lehnen den Header ab (REQ-SEC-029).

### Removed

- **Ingest-Gateway: die alten SC-Extractor-Endpunkte sind entfernt.** `/v1/refinery-extract` und
  `/v1/blueprint-preview` gibt es nicht mehr, auch ohne `410`-Hinweis; der SC Extractor sendet seit
  2.10.0 über den Datenaustausch. Mit ihnen entfallen `IRI_INGEST_LEGACY_ENDPOINTS_ENABLED`,
  `IRI_INGEST_ALLOWED_CLIENT_IDS`, `IRI_INGEST_CLIENT_AUDIT_ONLY`, `IRI_INGEST_REQUIRED_SCOPE` und
  `IRI_INGEST_ALLOWED_TOOLS` samt Alarm `IngestUnknownClient` (#2092).

- **Ingest-Gateway: kein generiertes OpenAPI-Dokument mehr.** springdoc, `/v3/api-docs` und
  `ingest/src/main/resources/api/openapi.json` sind entfernt; der Vertrag des Gateways ist allein
  `exchange-v1.openapi.json`, weiterhin unter `/exchange/v1/openapi.json`.

### Fixed

- **Organigramm: Entfernen eines OL-Mitglieds löscht die Grand-Admiral-Zuordnung zuverlässig.** War die
  Organisationsleitung bereits als Hibernate-Proxy geladen, blieb die Zuordnung stehen; das Setzen und
  Entfernen des Grand Admirals scheiterte in diesem Fall ebenfalls.
- **Datenaustausch: das Gateway holt nach einer Abweisung seines eigenen Tokens ein neues.** Lehnt
  das Backend das Dienstkonto-Token des Ingest-Gateways ab (`401`/`403` ohne Austausch-Code),
  verwirft das Gateway es, sodass die nächste Anfrage ein frisches holt, statt bis zum Ablauf
  weiter zu scheitern.

- **Aufträge: „Items bearbeiten" speichert wieder ohne Umweg.** Die bearbeitende Einheit war im
  Bearbeiten-Formular nicht vorausgewählt; weil das Feld Pflicht ist, verwarf der Browser das Speichern
  ohne sichtbare Meldung, und z. B. ein entferntes Item blieb im Auftrag. Beide Einheiten sind jetzt
  mit den gespeicherten Werten vorbelegt (REQ-ORDERS-032).

- **Keycloak: SC Extractor 2.10.0 kann sich wieder anmelden.** Die Geräteanmeldung scheiterte in
  Produktion mit `400 not_allowed`, weil die Standardrolle `default-roles-iri` kein
  `offline_access` enthielt; der Realm-Provisioner ergänzt die Rolle dort und am Client-Scope
  `offline_access` und nimmt sie nie wieder weg (ADR-0202 Amendment 5, REQ-OPS-033).

## Archiv

Ältere Versionen (vor v1.13.1, d. h. v1.13.0 und älter) sind in [CHANGELOG-ARCHIVE.md](CHANGELOG-ARCHIVE.md) archiviert.
