# Changelog

## [Unreleased]

### Added

- **Änderungen aus der App und aus dem Browser sehen sich jetzt gegenseitig.** Das Backend hat einen Live-Sync-Strom für die App bekommen und hängt am selben Redis-Kanal wie die Weboberfläche: eine Bearbeitung im Browser erscheint in der App ohne manuelles Neuladen, und eine Buchung in der App aktualisiert jede offene Browser-Ansicht. Vorher war beides blind füreinander. Bearbeiter-Punkte bleiben absichtlich der Weboberfläche vorbehalten (ADR-0143, REQ-FE-019).

- **Die Schreibpfade der App sind am öffentlichen API-Endpunkt freigegeben.** Eine Änderung an der Edge-Konfiguration schaltet alle Phase-3-Pfade gemeinsam frei; die nächtliche Prüfung deckt sie ab diesem Zeitpunkt mit ab (REQ-SEC-037, REQ-OBS-012).

- **Die Kontoeinstellungen der Kartellbank sind für die App freigegeben und eingefroren.** Zielsaldo setzen und die Sichtbarkeit eines Kontos regeln — nur für die verantwortliche Kontoinhaberin. Die Bankangestellten-Oberfläche (Ein- und Auszahlungen, Überweisungen) bleibt ausgeschlossen (REQ-API-009, REQ-SEC-037).

- **Einsatz-Finanzen und die Auszahlungsbestätigung sind für die App freigegeben und eingefroren.** Einnahmen und Ausgaben zu einem Einsatz buchen, ändern und löschen, dazu die Bestätigung einer Auszahlung durch den Einsatzleiter (REQ-API-009, REQ-SEC-037).

- **Die eigene Einsatz-Teilnahme ist für die App freigegeben und eingefroren.** Anmelden, abmelden, ein- und auschecken sowie die Auszahlungspräferenz — jeweils nur für den eigenen Eintrag. Die Einsatzplanung (Einheiten, Crews, Ablauf) bleibt ausgeschlossen (REQ-API-009, REQ-SEC-037).

- **Auftrags-Zuweisung und Statuswechsel sind für die App freigegeben und eingefroren.** Sich selbst auf einen Auftrag setzen, wieder herunternehmen, die eigene Notiz dazu und — für Logistiker — der Statuswechsel. Die Übergaben, die Produktionsmeldungen und die übrige Bearbeitung bleiben ausgeschlossen (REQ-API-009, REQ-SEC-037).

- **Der eingefrorene API-Vertrag deckt jetzt auch Query-Parameter und Pflicht-Enums in Anfragen ab.** Ein umbenannter Parameter oder eine umbenannte Enum-Konstante brach eine ausgelieferte App bisher, ohne den Build zu brechen (REQ-API-009).

- **Die Lager-Buchungen der App sind freigegeben und eingefroren.** Einbuchen, Ausbuchen (verwerfen, übergeben, verkaufen), persönlich→geteilt umbuchen und die Notiz, dazu die vier Auswahllisten dahinter. Die Sammel-Endpunkte und die Alle-Mitglieder-Liste bleiben ausgeschlossen (REQ-API-009, REQ-SEC-037).

- **Der Hangar der App darf schreiben.** Eigene Schiffe anlegen, ändern und löschen sind am API-vhost freigegeben und eingefroren, dazu die beiden Auswahllisten (Schiffstypen, Heimatorte). Die Importe und die Admin-Sicht auf fremde Hangars bleiben ausgeschlossen (REQ-API-009, REQ-SEC-037).

- **Die Blueprints der App sind am API-vhost freigegeben und eingefroren.** Eigene Blueprints lesen, hinzufügen, Notiz ändern und löschen, dazu die Baubarkeits-Abfrage und die Produktsuche dahinter. Der Datei-Import bleibt Phase 4 (REQ-API-009).

- **Die App darf jetzt schreiben — der erste Schritt.** „Mein Inventar" (eigener Bestand: anlegen, ändern, löschen) ist am API-vhost freigegeben und im eingefrorenen Client-Vertrag festgehalten. Freigeschaltet wird der Weg zur Produktion gesammelt am Ende von Phase 3 (Runbook, Phase I).

- **Der Vertragstest prüft jetzt auch die Anfrageseite.** Ein Pflichtfeld, das eine Schreib-Schnittstelle neu verlangt, ist für jede bereits ausgelieferte App ein 400 — der Test lässt das nicht mehr durch (REQ-API-009, ADR-0136).

### Changed

- **Die SpotBugs-Gradle-Plugin-Version wurde auf 6.5.11 angehoben.** Reiner Bugfix-Bump ohne bekannte Sicherheitslücke, im Rahmen des routinemäßigen Abhängigkeits-Audits.

### Changed

- **Der nächtliche Edge-Probe deckt jetzt die vollständige Phase-2-Allowlist ab**, inklusive der einen Stelle, an der nur das Verb zwei Oberflächen trennt: `POST /api/v1/orders` ist der öffentliche Antragsweg und muss auf dem API-vhost mit 405 abgewiesen werden. Dazu steht im Runbook jetzt eine Schritt-für-Schritt-Anleitung für den einen verbleibenden manuellen Schritt (Phase H).

### Changed

- **Die letzten Phase-2-Lesepfade sind eingefroren und für den API-vhost vorbereitet:** Lager-Baum, Auftragswarteschlange samt Detail und die Org-Bank (Salden, Kontodetail, Buchungen). Alles als exakte Pfade, und die schreibende Hälfte bleibt draußen — bei `/api/v1/orders` trennt nur das Verb den öffentlichen Antragsweg von der Warteschlangenansicht, weshalb der Read-Only-Schutz des vhosts diese Familie jetzt mit abdeckt.
- **Die Hangar-Lesepfade sind eingefroren und für den API-vhost vorbereitet.** Die eigene Schiffsliste und die Aggregation über die aktive Org-Einheit stehen im REQ-API-009-Vertragssatz — als exakte Pfade, denn dieselbe Familie trägt die Schiffe *aller* Mitglieder, die Admin-Ansicht und die Schreibverben. Der Vertrags-Guard prüft jetzt auch verschachtelte Objekte, nicht nur Listen: bisher war bei einem Schiff nur `shipType` eingefroren, nicht dessen `name` — also genau das, was auf der Karte steht.
- **Die Ankündigung ist für ausgelieferte Clients eingefroren und für den API-vhost vorbereitet.** `GET /api/v1/announcement` steht im REQ-API-009-Vertragssatz — einschließlich der 204-Antwort, wenn es nichts anzukündigen gibt: ein Client, der die leere Antwort als Fehler liest, zeigt eine Störung, wo „kein Banner" richtig wäre. Der Pfad ist exakt freigeschaltet, nicht als Präfix, weil darunter die Admin-Ansicht und der Schreibpfad derselben Zeile liegen.
- **Die Benachrichtigungs-Lesepfade sind für ausgelieferte Clients eingefroren und für den API-vhost vorbereitet.** Posteingang, Ungelesen-Zähler und der SSE-Push stehen im REQ-API-009-Vertragssatz; die schreibende Hälfte der Familie bleibt draußen und wird zusätzlich per 405 abgewiesen. Der Stream setzt jetzt `X-Accel-Buffering: no`, damit ein nginx davor die Ereignisse nicht puffert — sonst kommen sie verspätet, gebündelt oder gar nicht an, und das sieht wie ein kaputter Push aus statt wie eine Proxy-Einstellung.
- **Die Operationen-Lesepfade sind für ausgelieferte Clients eingefroren und für den API-vhost vorbereitet.** `GET /api/v1/operations/search`, `/{id}`, `/{id}/finance-summary` und `/{id}/payouts` stehen im REQ-API-009-Vertragssatz; der Vertrags-Guard prüft jetzt auch die Zeilen **eingebetteter** Listen, vorher war nur der Listenname eingefroren und ein umbenanntes `shareAmount` wäre unbemerkt durchgegangen. Die Operationen-Familie ist auf dem vhost ebenfalls schreibgeschützt (405). Braucht wieder einen manuellen Konfigurationsschritt (Runbook § D.3a).
- **`GET /api/v1/users/me` ist für die App freigeschaltet — für genau ein Feld.** Die Auszahlungszeilen einer Operation sind auf die Backend-Benutzer-ID verschlüsselt, nicht auf den Keycloak-`sub` der App und nicht auf einen Anzeigenamen; ohne `id` kann die App einem Mitglied nicht sagen, welche der Zeilen seine ist. Nur `id` ist eingefroren, der Rest der Antwort bleibt frei.
- **Der nächtliche Edge-Probe prüft jetzt die komplette Statustabelle des API-vhosts von außen.** Bisher konnte niemand feststellen, ob ein Pfad überhaupt freigeschaltet wurde — die Allowlist liegt in der NPM-Datenbank, und ein nie eingefügter Pfad antwortet mit 404 wie ein absichtlich gesperrter. Genau das hatte zuletzt einen leeren Einsatz-Detailbildschirm verursacht. Der Lauf schlägt in beide Richtungen an: 2xx, wo eine Abweisung stehen muss, und 404, wo ein Status stehen muss.

### Changed

- **Die Rollout-Prüfung des API-vhosts nannte für die beiden Finanz-Lesepfade den falschen Status.** Ohne Token antworten sie mit **403**, nicht mit 401: unterhalb von `/api/v1/missions/**` ist die Filterkette `permitAll`, die Abweisung entsteht erst an der Methode und wird dort als 403 gerendert. Beide Pfade sind unverändert dicht — nur die Zahl in der Prüftabelle war falsch, und ein neuer Test hält sie jetzt fest (REQ-SEC-037, Runbook § D.3a).

### Changed

- **Eine neue Enum-Konstante kann eine ausgelieferte App nicht mehr unbemerkt lahmlegen.** Jede *pflichtige* Enum-Eigenschaft, die von einer Vertragsoperation aus erreichbar ist, ist konstantenweise eingefroren; eine Erweiterung lässt jetzt den Backend-Build scheitern. Grund: ein streng parsender Client verliert bei einer unbekannten Konstante nicht das Feld, sondern die **ganze Antwort** — gemessen an der Android-App machte ein einzelner unbekannter `JobTypeDto.archetype` die komplette Einsatz-Detailantwort unlesbar, während die Liste weiterlief. Die Reihenfolge „App-Build zuerst, dann Konstante“ wird damit erzwungen statt gehofft (REQ-API-009).

### Changed

- **Einsatzdetail und Finanzen sind für ausgelieferte Clients eingefroren und auf dem API-vhost freigeschaltet.** `GET /api/v1/missions/{id}` sowie die beiden Finanz-Lesepfade stehen im REQ-API-009-Vertragssatz. Die vhost-Einträge sind UUID-förmig und **verankert**, weil unterhalb von `{id}` fast nur Schreibpfade liegen; zusätzlich weist der vhost jede Nicht-`GET`-Anfrage unterhalb von `/api/v1/missions` mit 405 ab, da eine pfadbasierte Allowlist die Methode nicht sieht und `/missions/{id}` auch `PUT` und `DELETE` beantwortet. Braucht wieder einen manuellen Konfigurationsschritt (Runbook § D.3a).

### Changed

- **Die anonyme Oberfläche des API-vhosts ist jetzt vollständig aufgezählt statt beiläufig.** Zwei Endpunkte antworten dort bewusst ohne Token — der Nutzungsbedingungstext und die Einsatzsuche, deren gastredigierte Zeilen die öffentliche Startseite ohnehin anzeigt. Beide stehen mit Begründung in REQ-SEC-037. Die Prüfanweisung im Rollout-Runbook nannte pauschal „401 ist bestanden“ und schlug deshalb bei genau diesen Pfaden falschen Alarm; sie liest den erwarteten Status jetzt aus einer Tabelle pro Pfad.

## [v1.5.61](https://github.com/krt-profit/basetool/releases/tag/v1.5.61) - 2026-08-21

### Changed

- **Die Einsatzsuche der API gehört jetzt zum eingefrorenen Vertrag für ausgelieferte Clients.** `GET /api/v1/missions/search` steht im REQ-API-009-Vertragssatz und auf der Allowlist des API-vhosts, damit die Android-App sie erreicht — als exakter Pfad, nicht als Präfix, weil die Allowlist die HTTP-Methode nicht sieht. Der Vertrags-Guard prüft bei paginierten Antworten jetzt beide Ebenen; bisher hätte er nur den Umschlag eingefroren und ein entferntes Zeilenfeld wäre unbemerkt durchgegangen. Der vhost braucht dafür einen manuellen Konfigurationsschritt (Runbook § D.3a).

## [v1.5.60](https://github.com/krt-profit/basetool/releases/tag/v1.5.60) - 2026-08-21

## [v1.5.59](https://github.com/krt-profit/basetool/releases/tag/v1.5.59) - 2026-08-21

### Changed

- **Spring Boot auf 4.1.1 angehoben (Wartungs-Release).** Es bringt unter anderem Spring Framework 7.0.9, Spring Security 7.1.1, Hibernate 7.4.5, Tomcat 11.0.24, Netty 4.2.17.Final und den PostgreSQL-Treiber 42.7.13 mit; die drei temporären CVE-Overrides für Tomcat, Netty und den PostgreSQL-Treiber entfallen damit, weil Spring Boot genau diese Versionen jetzt selbst ausliefert. Rein intern, keine Auswirkung auf die Oberfläche.

### Fixed

- **Der Push-Kanal funktioniert wieder — er war unbemerkt tot.** Ein ETag-Filter puffert jede Antwort, um eine Prüfsumme zu bilden, und schrieb sie bei laufender Nebenläufigkeit nie zurück: Benachrichtigungs-Ströme nahmen Verbindungen an und lieferten dauerhaft kein einziges Byte. Sichtbar war das nirgends, weil die Metrik erzeugte Verbindungen zählt und nicht angekommene Daten (#1653).

- **Eine Anmeldung über die Android-App entzog Administratoren ihre Admin-Rolle in der Datenbank.** Der Rollensatz wurde bei jeder Anmeldung aus dem Token überschrieben, und das App-Token führt `Admin` bewusst nicht mit — bis zur nächsten Web-Anfrage sahen Hintergrundjobs, Benachrichtigungsregeln und Mitgliederlisten den verkürzten Satz. Tokens von Clients mit absichtlich unvollständigem Rollen-Scope schreiben jetzt gar keine Rollen mehr; die Anfrage selbst wird weiterhin nur mit den Rollen aus dem Token autorisiert, die App bekommt also nach wie vor keine Administrationsrechte. Neue optionale Umgebungsvariable `APP_SECURITY_PARTIAL_ROLE_SCOPE_CLIENT_IDS` (REQ-SEC-036).

- **Der Keycloak-Client der Android-App gab gar keine Rollen mit, wodurch jede Anmeldung über die App das Konto auf `Gast` zurücksetzte** — auch für die Weboberfläche, weil der Rollensatz bei jeder Anmeldung aus dem Token überschrieben wird. Das Provisionierungsskript vergibt jetzt die vier Mitgliedsrollen (KRT Member, Officer, Bank Employee, Bank Management) und nimmt alles andere wieder weg; `Admin` bleibt bewusst draußen, da die App keine Administrationsansichten hat (REQ-SEC-035). Das Produktionsrealm braucht dafür einen erneuten Skriptlauf, bevor die App ausgeliefert wird.

## [v1.5.58](https://github.com/krt-profit/basetool/releases/tag/v1.5.58) - 2026-08-20

### Added

- **Testversionen lassen sich über eine eigene Aktion „Promote to testing" ausliefern.** Sie hängt — wie die Produktions-Promotion — ein zusätzliches Tag `:testing` an einen bereits gebauten, signierten Digest, ohne neu zu bauen; ein Testhost mit `deploy.sh --tag testing` zieht ihn beim nächsten Timer-Lauf. Signaturprüfung und der Gleichschritt aller fünf Artefakte sind identisch zur Produktion, nur die Reviewer-Freigabe entfällt (REQ-OPS-022).

- **Zwei neue optionale Umgebungsvariablen `IRI_TRUSTSTORE_HOST_PATH` und `IRI_EXTRA_JAVA_OPTS`.** Steht vor dem Issuer ein selbstsigniertes Zertifikat, scheitert der Start von Backend, Frontend und Ingest an `PKIX path building failed` — die OIDC-Metadaten werden gegen den JVM-Standard-Truststore geprüft, den die SSL-Bundles des Projekts nicht abdecken. Die Variablen hängen einen eigenen Truststore ein; ungesetzt sind beide wirkungslos (REQ-OPS-022).

- **Neue optionale Umgebungsvariable `IRI_KEYCLOAK_HOST_ALIAS`.** Hinter einem NAT ohne Hairpin zeigt der öffentliche Keycloak-Name auf die WAN-Adresse, die die eigenen Container nicht erreichen — Backend, Frontend und Ingest holen dort beim Start ihre OIDC-Metadaten und werden nie gesund, worauf das Health-Gate die Auslieferung zurückrollt. Die Variable trägt einen `extra_hosts`-Eintrag nach, der den Namen auf den lokalen Reverse Proxy zeigen lässt; ungesetzt ist sie wirkungslos (REQ-OPS-022).

- **Zwei neue optionale Umgebungsvariablen für Umgebungen unter eigener Domain: `IRI_KEYCLOAK_HOSTNAME` und `IRI_KEYCLOAK_ISSUER_URI`.** Damit läuft dasselbe ausgelieferte Config-Bundle unter einem anderen Hostnamen, was bisher an zwei fest verdrahteten Werten scheiterte. Beide sind entweder zusammen zu setzen oder gar nicht; ungesetzt greifen unverändert die Produktionswerte (REQ-OPS-022).

### Fixed

- **Eine aus einem Windows-Klon kopierte `.env` ließ das Deployment mit einer irreführenden Meldung abbrechen.** `.env.example` hatte keine `eol=lf`-Regel, wurde also mit CRLF ausgecheckt; `deploy.sh` liest den Keystore-Pfad mit `grep`/`cut` daraus und bekam ihn mit angehängtem Wagenrücklauf — der Abbruch lautete dann `required file missing` für eine Datei, die vorhanden war. `.gitattributes` deckt jetzt `.env.example` und `*.ftl` mit ab.

- **Das Deployment-Runbook verlangte für den GHCR-Pull-Token einen Fine-grained PAT — den GHCR gar nicht annimmt.** GitHub Packages unterstützt ausschließlich klassische Tokens; wer der Anleitung folgte, landete bei einem `denied` beim `docker login`, ohne dass am Token etwas falsch war. §5.4 nennt jetzt den Classic PAT mit `read:packages`, den nötigen SSO-Freigabeschritt und den Preis dieser Token-Art (kontoweiter Lesezugriff statt Repo-Beschränkung).

## [v1.5.57](https://github.com/krt-profit/basetool/releases/tag/v1.5.57) - 2026-08-20

### Added

- **Beide Import-Seiten verlinken jetzt den Basetool SC Extractor.** Auf „Meine Blueprints“ neben „JSON importieren“ und auf „Neuer Raffinerieauftrag“ neben „Aus Screenshot-Extract importieren (JSON)“ führt ein Knopf direkt zum neuesten Release der Desktop-App, die genau diese Dateien erzeugt — wer sie noch nicht hat, musste sie bisher außerhalb des Tools suchen. Der Link öffnet sich in einem neuen Tab; die Blueprint-Liste bzw. ein bereits vorausgefülltes Auftragsformular bleibt stehen (REQ-INV-038, REQ-REFINERY-019).

## [v1.5.56](https://github.com/krt-profit/basetool/releases/tag/v1.5.56) - 2026-08-20

### Changed

- **Die Freigabe-Limits stehen in der Kontoansicht der Bank jetzt sichtbar über der Konto-Info.** Sie steckten bisher in der standardmäßig zugeklappten Kachel „Konto-Info", die sich nach jeder Buchung wieder schließt — ein Bankmitarbeiter musste also erst aufklappen, um die Grenzen zu sehen, gegen die er bucht. Sind Limits eingerichtet, zeigt die Kontoansicht sie jetzt als eigene Kachel; die Anzeige bleibt für alle schreibgeschützt, eingerichtet werden Limits weiterhin nur in der Org-Einheits-Bank (REQ-BANK-041).

- **Die Sichtbarkeits-Einstellung eines Kontos benennt die Zielgruppe genauer.** Bei Staffel- und Bereichskonten heißt die Zeile jetzt „Alle Mitglieder der Org-Einheit" statt „Alle Mitglieder" — passend zum gleichnamigen Freigabe-Limit, denn die Freigabe greift nur für Mitglieder der besitzenden Org-Einheit. Bei Sonderkonten bleibt es „Alle Mitglieder", weil dort tatsächlich alle KRT-Mitglieder gemeint sind (REQ-BANK-035).

- **Die Spalte „Offen“ ist aus den *Aggregierten Materialien* eines Item-Auftrags verschwunden.** Sie zeigte den Eintragungs-Rest auf Basis des **vollen** Auftragsbedarfs, während „Gesamtmenge“ daneben nur den Bedarf der **noch nicht hergestellten** Einheiten meint — sobald Herstellung gebucht war, stand dort mehr „offen“ als überhaupt „gebraucht“ (bei Auftrag #75: 71,52 SCU offen gegen 56,2 SCU Gesamtmenge, ohne eine einzige Eintragung). Der „Eintragen“-Knopf sitzt jetzt in der Spalte „Eingetragen“; die maximal eintragbare Menge steht unverändert im Eintragen-Dialog. Bei Material-Aufträgen bleibt „Offen“ wie bisher (REQ-ORDERS-026).

### Fixed

- **Start- und Endzeit im Teilnehmer-Dialog eines Einsatzes liefen aus ihrer Spalte.** Datums- und Uhrzeitfeld haben eine feste Breite, die nebeneinander nicht in den 600 Pixel breiten Dialog passte: Die Uhrzeit ragte rund 13 Pixel über ihre Spalte hinaus, der Abstand zwischen „Startzeit“ und „Endzeit“ schrumpfte auf 3 Pixel und das Endzeit-Feld klebte am Dialogrand. Passt ein Paar nicht mehr neben das andere, rückt es jetzt in die nächste Zeile — mit dem gleichen Abstand wie alle übrigen Felder (REQ-UI-013).

- **Der Dev- und E2E-Stack startete sein Backend nicht mehr.** Seit v1.5.55 setzte das Dev-Profil den JWKS-Schlüssel unter Springs eigenem Namensraum mit leerem Standardwert; Spring wertet leer nicht als „nicht gesetzt", sondern bricht den Start mit `jwkSetUri cannot be empty` ab. Der Backend-Container wurde nie gesund, damit kam der gesamte E2E-Stack nicht hoch und jeder Testlauf scheiterte an der Stack-Bereitstellung statt an seiner eigenen Prüfung. Der Schalter liegt jetzt wie in der Produktion auf `app.security.jwt.jwk-set-uri`, wo leer wieder „aus" bedeutet. Produktion war nie betroffen (REQ-SEC-024).

## [v1.5.55](https://github.com/krt-profit/basetool/releases/tag/v1.5.55) - 2026-08-19

### Added

- **Eine irrtümlich abgelehnte Registrierung kann wieder geöffnet werden.** Unter `/admin/discord-registrations` listet eine zweite Tabelle die abgelehnten Registrierungen samt Zeitpunkt der Ablehnung; ein Admin holt eine davon per „Wiederöffnen“ zurück in die Warteschlange und gibt sie anschließend wie gewohnt frei. Bisher war eine Ablehnung endgültig — rückgängig machen ließ sie sich nur mit einem direkten Eingriff in die Datenbank oder durch Löschen des Kontos. Die Wiederöffnung wird protokolliert und ist für bereits freigegebene Konten gesperrt (REQ-SEC-034, ADR-0140).

### Fixed

- **Abgelehnte Registrierungen sahen weiter „Freigabe ausstehend".** Wartende und abgelehnte Konten landeten auf derselben Seite, und die zeigte unabhängig vom Status die Wartemeldung — abgelehnte Nutzer warteten deshalb auf eine Freigabe, die nie kommt, und meldeten das als hängende Freigabe. Die Seite unterscheidet jetzt: Abgelehnte sehen einen Ablehnungshinweis ohne automatische Freischaltung und den Verweis auf einen Administrator im Discord, Wartende die unveränderte Wartemeldung, und ein bereits freigegebenes Konto wird direkt ins Tool geleitet. Fällt die Ablehnung, während die Seite offen ist, wechselt der Text ohne Neuladen; danach wird nicht mehr auf eine Freigabe geprüft (REQ-SEC-017).

## [v1.5.54](https://github.com/krt-profit/basetool/releases/tag/v1.5.54) - 2026-08-19

### Changed

- **Der Image-Bau eines Releases läuft nur noch einmal statt zweimal.** Release-Commit und `v*`-Tag zeigen auf denselben Commit, also übernimmt der Tag-Lauf jetzt die bereits gebauten, gescannten und signierten Images des main-Laufs und hängt nur die Versions-Tags daran — nach cosign-Prüfung, sonst wird regulär gebaut. Für den Betrieb heißt das: `:1.5.x` und `:sha-<kurz>` sind ab sofort derselbe Digest. Zusätzlich entfällt der Docker-Layer-Cache, der mehr Zeit kostete als er sparte. Ein Release dauert damit rund 5 statt 12:45 Minuten (REQ-OPS-021, ADR-0137).

- **Keycloak auf 26.7.2 angehoben (Sicherheitsupdate).** Das Patch-Release schließt sieben Schwachstellen im Anmeldedienst — darunter eine Übernahme fremder Konten ohne Anmeldung über den Passwort-Zurücksetzen-Ablauf, eine Übernahme über einen vorhersagbaren Verknüpfungs-Hash beim Verbinden von Konten und die Preisgabe rotierter Client-Geheimnisse über die Verwaltungsschnittstelle. Keine Funktions- oder Konfigurationsänderung: Das gepinnte Container-Image (`quay.io/keycloak/keycloak:26.7`-Digest, weiterhin JDK 21) und die SPI-Artefakte des `keycloak-spi`-Moduls ziehen mit; die Discord-Anmeldung und -Kontoverknüpfung laufen unverändert weiter. **Deploy-Hinweis:** Eine geänderte Keycloak-Image-Pinnung ist operator-gated — der Deploy wendet sie nicht selbständig an, sondern muss einmal mit `deploy.sh --force` durchgesetzt werden; der Keycloak-Container startet dabei neu.

- **Die Nutzungsbedingungen stehen jetzt an genau einer Stelle.** Der Text lag bisher im Sprachbundle der Weboberfläche; er liegt jetzt im Backend und wird über eine eigene Schnittstelle ausgeliefert, aus der sowohl die Webseite `/terms` als auch die Zustimmungsschranke rendern — und künftig die Android-App. Damit kann keine Fassung mehr von der abweichen, für die die Zustimmung gespeichert wird. Am Wortlaut ändert sich nichts, die gespeicherten Zustimmungen bleiben gültig, und die Seite sieht unverändert aus. Der Link zur Datenschutzerklärung steht jetzt unter dem Dokument statt mitten im Datenschutz-Absatz (ADR-0138, REQ-SEC-028).

## [v1.5.53](https://github.com/krt-profit/basetool/releases/tag/v1.5.53) - 2026-08-19

### Changed

- **Die App trägt jetzt ihr eigenes Logo.** Kopfzeile, Browser-Tab, Startbildschirm-Symbol und die Anmeldeseite zeigen das neue Basetool-Zeichen statt der Kartell-Marke; das Kartell-Logo bleibt auf den erzeugten PDF-Dokumenten, wo die Organisation gemeint ist. Das Design-System ist dabei auf den aktuellen Stand gehoben worden (REQ-UI-019).

## [v1.5.52](https://github.com/krt-profit/basetool/releases/tag/v1.5.52) - 2026-08-19

### Changed

- **Gastmodus der App gestrichen — die anonymen Schreibpfade bleiben dauerhaft zu.** Die öffentliche API-Allowlist hielt `POST /api/v1/orders/items`, die Gast-Teilnehmer-Mutationen und die redigierten Browsing-Zwillinge für den Gastmodus der Android-App in Reserve. Der Modus entfällt (jeder Nutzer meldet sich an), also werden diese Pfade auf dem `api`-vhost nie freigeschaltet. Für die Weboberfläche ändert sich nichts — sie erreicht das Backend intern.

### Fixed

- **Der nächtliche Fehlalarm zur DNS-Auflösung der App-Schnittstelle ist behoben.** Die beiden neuen Überwachungsprüfungen für `api.profit-base.online` schlugen ab der ersten Messung fehl, obwohl die Auflösung einwandfrei funktioniert — der Name ist ein Alias auf die Hauptdomain, und die Prüfregel ließ nur einen direkten Adresseintrag gelten. Sie verlangt jetzt, dass die Antwort überhaupt eine Adresse liefert; echte Ausfälle (unbekannter Name, fehlender Eintrag, ins Leere zeigender Alias) schlagen unverändert an. Keine Auswirkung auf die Oberfläche (REQ-OBS-012).

## [v1.5.51](https://github.com/krt-profit/basetool/releases/tag/v1.5.51) - 2026-08-18

## [v1.5.50](https://github.com/krt-profit/basetool/releases/tag/v1.5.50) - 2026-08-18

### Fixed

- **Die Zuordnung von Anfragen zur Client-Software hat nichts gezählt.** Der dafür zuständige Filter lief eine Position zu früh — vor der Authentifizierung, wo noch keine Anmeldung sichtbar ist — und lieferte deshalb seit seiner Einführung gar keine Messwerte. Er sitzt jetzt an der richtigen Stelle; ein Test hält beide Seiten des schmalen Fensters fest. Die Aufschlüsselung fehlgeschlagener Anmeldungen war davon nicht betroffen (REQ-OBS-018).

## [v1.5.49](https://github.com/krt-profit/basetool/releases/tag/v1.5.49) - 2026-08-18

### Changed

- **Das Backend bekommt einen eigenen Netzwerkpfad zum Randserver, damit die geplante App-Schnittstelle erreichbar wird.** Bisher lag das Backend auf keinem Proxy-Netz — der neue Vhost `api.profit-base.online` konnte den Dienst deshalb gar nicht auflösen und beantwortete jeden Aufruf mit der Wartungsseite. Keine Auswirkung auf die Oberfläche (ADR-0135). **Deploy-Hinweis:** die Änderung erweitert die Netzwerk-Topologie, deshalb setzt der Deploy den gesamten Stack einmal neu auf — kurze Komplettunterbrechung, bitte in einem Wartungsfenster ausrollen.

## [v1.5.48](https://github.com/krt-profit/basetool/releases/tag/v1.5.48) - 2026-08-18

### Fixed

- **Das Frontend wurde nach dem letzten Deploy nicht mehr gesund und der Rollout ist zurückgerollt.** Die Bereitschaftsprüfung des Frontends fragt das Backend ab — über genau den Port, dessen technische Statusdaten mit dem vorherigen Release auf einen internen Port umgezogen sind (ADR-0134). Sie zeigt jetzt auf den richtigen Port; ein Test hält beide Seiten zusammen, damit ein weiterer Portwechsel nicht wieder unbemerkt bleibt.

## [v1.5.47](https://github.com/krt-profit/basetool/releases/tag/v1.5.47) - 2026-08-18

### Added

- **Vorbereitung für die Android-App: Der Betrieb sieht jetzt, welche Client-Software die Schnittstelle nutzt und warum eine Anmeldung scheitert.** Zwei neue Messwerte ordnen jede angemeldete Anfrage ihrem Programm zu — eine unbekannte Software löst eine Warnung aus — und schlüsseln abgelehnte Anmeldungen nach Ursache auf (abgelaufenes Token, fehlerhafter Kopfeintrag …). Die Überwachung der geplanten öffentlichen Schnittstelle ist vorbereitet, aber bewusst noch abgeschaltet, bis der Host existiert. Keine Auswirkung auf die Oberfläche (ADR-0135, REQ-OBS-018).

- **Jedes Benutzerkonto hat jetzt ein eigenes Anfragekontingent für Änderungen.** Bisher wurde nur pro Netzwerkadresse begrenzt — was Mitglieder hinter einem gemeinsamen Anschluss gegenseitig ausbremst und einen Aufrufer mit wechselnden Adressen gar nicht erfasst. Das neue Kontingent (120 Änderungen pro Minute) hängt an der Anmeldung und liegt weit über dem, was normale Bedienung erzeugt (REQ-SEC-033).

### Changed

- **Bankdaten, Mitgliederdaten und Benachrichtigungen dürfen von Zwischenspeichern nicht mehr abgelegt werden.** Bisher durfte ein Proxy oder Browser-Cache diese Antworten aufbewahren, solange er vor der Wiederverwendung nachfragt; jetzt dürfen sie gar nicht erst gespeichert werden. Alle übrigen Abfragen bleiben unverändert (REQ-SEC-031).

- **Die Preis-Matrix ist nur noch angemeldet abrufbar, und ohne Anmeldung sind höchstens 1000 Einträge pro Seite möglich.** Die Matrix ist die größte Einzelantwort der Schnittstelle und war bisher offen erreichbar; genutzt wird sie ohnehin nur auf einer Seite, die eine Anmeldung verlangt. Zu große Seitenanfragen werden jetzt mit einer klaren Fehlermeldung abgelehnt, statt stillschweigend gekürzt zu werden (REQ-SEC-032).

- **Die technischen Statusdaten des Backends sind nicht mehr über den normalen Anwendungsport erreichbar.** Gesundheitsprüfung und Messwerte laufen jetzt über einen eigenen, ausschließlich intern erreichbaren Port — so wie es Frontend und Ingest schon länger tun. Damit liefert der Port, den die geplante öffentliche Schnittstelle weiterreicht, diese Daten gar nicht erst aus, statt sich allein auf eine Sperre am Randserver zu verlassen. Die Möglichkeit, während einer Störung kurzfristig die Protokolltiefe zu erhöhen, bleibt Administratoren erhalten. **Deploy-Hinweis:** Anwendungsabbild und Konfigurationspaket müssen gemeinsam ausgerollt werden — einzeln schlägt die Gesundheitsprüfung fehl und der Deploy rollt zurück (ADR-0134).

### Fixed

- **Die Rate-Limit-Zählung lässt sich nicht mehr durch einen gefälschten Absender aushebeln.** Das Backend ermittelt die echte Absenderadresse jetzt so, dass nur die eigenen vorgeschalteten Server dazu beitragen können. Im heutigen Betrieb ändert sich nichts — die Lücke wäre erst mit der geplanten öffentlichen Schnittstelle aufgegangen (REQ-SEC-011).

## [v1.5.46](https://github.com/krt-profit/basetool/releases/tag/v1.5.46) - 2026-08-18

### Added

- **Auszahlungsanträge haben jetzt ein Feld "Empfänger".** Wer über die Org-Einheits-Bank eine Auszahlung beantragt, kann angeben, wer das Geld bekommt — vorbelegt mit dir selbst. Bisher wurde beim Buchen immer der Antragsteller als Empfänger eingetragen; ging die Auszahlung an jemand anderen, ließ sich das weder eintragen noch von der Bank korrigieren. Der Empfänger erscheint auch beim Aufklappen des Antrags.

- **Eigene Anträge lassen sich aufklappen und bearbeiten.** Unter "Meine Anträge" zeigt ein Pfeil vor der Zeile Begründung, Notiz und Empfänger — wie in der Antragsliste der Bank und bei den fremden Anträgen. Solange der Antrag noch aussteht und noch nicht freigegeben wurde, lässt er sich über "Bearbeiten" korrigieren (Betrag, Begründung, Notiz, Empfänger, Zielkonto), statt ihn zurückziehen und neu stellen zu müssen. Erhöhst du den Betrag über dein Freigabe-Limit, wird die Freigabepflicht neu bewertet. Konto und Vorgang bleiben fest; dafür ziehst du den Antrag weiterhin zurück und stellst ihn neu. Ist die Freigabe bereits erteilt, ist der Antrag gesperrt — sie galt für den ursprünglichen Betrag. Die "Notiz Bankmitarbeiter" bleibt hier bewusst unsichtbar, sie ist bankintern.

- **Das Freigabe-Limit "Alle Mitglieder" heißt jetzt "Alle Mitglieder der Org-Einheit".** Es galt schon immer nur für Mitglieder der Org-Einheit, zu der das Konto gehört; der alte Name legte etwas Weiteres nahe. Reine Umbenennung, keine Verhaltensänderung.

- **Bankmitarbeiter können jetzt eine eigene Notiz zu einer Buchung erfassen.** Beim Bestätigen eines Antrags und bei jeder direkten Einzahlung, Auszahlung und jedem Transfer gibt es das Feld "Notiz Bankmitarbeiter" für interne Anmerkungen ("in zwei Tranchen übergeben"). Sie erscheint überall dort, wo auch Notiz und Begründung stehen — Kontohistorie, Antragsliste, Fremde Anträge, Kontoauszug und 3-Monats-Report. Für Mitglieder einer Org-Einheit ist sie bewusst nicht sichtbar; sie ist eine bankinterne Anmerkung.

- **Fremde Anträge lassen sich jetzt aufklappen.** In der Freigabe-Liste der Org-Einheits-Bank zeigt ein Pfeil vor der Zeile Begründung und Notiz des Antrags — genau wie in der Antragsliste der Bank. Wer freigibt, entscheidet damit nicht mehr blind: beim KRT-Konto, dem Bankkonto und jedem Sonderkonto ist die Begründung Pflicht und war für den Freigebenden bisher nicht sichtbar.

### Changed

- **Die Nutzungsbedingungen benennen das Fan-Projekt jetzt ausdrücklich.** § 9 hält fest, dass das Basetool ein inoffizielles, nicht kommerzielles Fan-Projekt ohne Unterstützung von Cloud Imperium ist und Material aus dem offiziellen Star-Citizen-Fankit nur nach dessen Bedingungen nutzt. Da sich der Wortlaut ändert, wird die Zustimmung beim nächsten Login erneut abgefragt.

## [v1.5.45](https://github.com/krt-profit/basetool/releases/tag/v1.5.45) - 2026-08-17

### Added

- **Vorbereitung für die Android-App: Anmelde-Verfahren festgelegt und als Skript hinterlegt.** Die künftige App bekommt einen Keycloak-Zugang, bei dem nur ihr Erneuerungs-Token an das jeweilige Gerät gebunden ist — ein gestohlenes Token ist damit außerhalb des Handys wertlos. Ein neues Betreiber-Skript legt diesen Zugang samt Richtlinie an und prüft das Ergebnis. Rein vorbereitend, keine Auswirkung auf die Oberfläche (ADR-0131, REQ-SEC-030).

### Changed

- **Kontakt-E-Mail-Adresse aktualisiert.** Die Betreiber-Adresse in Impressum und Datenschutzerklärung (alle drei Sprachvarianten) sowie in CLA, Code of Conduct und Beitragsleitfaden lautet jetzt lucas.greuloch@gmail.com (vorher lucas.greuloch@pm.me).

### Fixed

- **Auswahlfelder verlieren ihre Auswahl nicht mehr unsichtbar.** Wer in einem Auswahlfeld (Halter, Material, Ort, Nutzer …) erneut hineinklickte und tippte, ohne danach einen Eintrag aus der Liste zu wählen, verlor beim Wegklicken die Auswahl im Hintergrund — das Feld zeigte weiterhin den zuvor gewählten Namen an. Das Formular ließ sich abschicken und wurde dann abgewiesen, ohne dass erkennbar war, woran es lag. Betraf unter anderem die Auszahlung eines Bank-Antrags, deren Freigabe-Häkchen genau diesen Klick erzwingt.

- **Meldungen zu ungültigen Eingaben benennen wieder das betroffene Feld.** Bei einem Prüffehler antwortete das Backend ohne Feldangabe, sodass die Oberfläche nur den Sammeltext „Einige Felder sind ungültig“ anzeigen konnte und im Server-Log gar kein Eintrag entstand — der Fehler war damit weder für Nutzer noch im Betrieb nachvollziehbar. Ursache war die Reihenfolge zweier Fehler-Handler (ADR-0132). Fehler werden jetzt wieder direkt am betroffenen Feld angezeigt und protokolliert.

## [v1.5.43](https://github.com/krt-profit/basetool/releases/tag/v1.5.43) - 2026-08-17

## [v1.5.42](https://github.com/krt-profit/basetool/releases/tag/v1.5.42) - 2026-08-17

### Changed

- **Redis läuft jetzt auf 8.10.0 (vorher 8.8.0).** Der Digest-Pin des `redis:8-alpine`-Images zeigte noch auf den Stand von Juni. Keine Konfigurationsänderung. **Deploy-Hinweis:** Der Redis-Container muss dafür neu gestartet werden.

- **Monitoring-Images aktualisiert:** Loki 3.7.6, Alloy 1.18.1 und redis_exporter 1.89.0. Grafana bleibt bewusst auf 13.0.2 — die OSS-Variante hat bis heute keinen 13.1.x-Tag veröffentlicht.

- **Die TypeScript-Typen der Backend-Schnittstelle erzeugt das Basetool jetzt selbst.** Statt des Pakets `openapi-typescript` läuft ein eigenes, abhängigkeitsfreies Skript; die erzeugte Datei schrumpft von 1,7 MB auf 96 KB und 22 npm-Pakete entfallen. Die Absicherung bleibt: Wird ein Feld im Backend umbenannt, bricht der Build, statt dass eine Auswahlliste stillschweigend leer bleibt. Rein intern, keine Auswirkung auf die Oberfläche (ADR-0130).

- **Der Typprüfer der Browser-Skripte läuft auf TypeScript 7.** Das war zuvor durch `openapi-typescript` blockiert. Nichts wird kompiliert oder gebündelt — die Skripte bleiben unverändert.

- **Fehlende Prüfungen auf nicht vorhandene Seitenelemente geschlossen.** Die Typprüfung deckte auf, dass mehrere Stellen in der Blaupausen-Ansicht und im Blaupausen-Import Elemente benutzten, ohne zu prüfen, ob sie überhaupt da sind. Fehlt eines — etwa weil ein Seitenteil nicht gerendert wurde —, brach dort bisher das Skript ab und die restliche Seite blieb tot; jetzt wird der betroffene Teil übersprungen und der Rest funktioniert weiter.

- **Build- und Bibliotheks-Abhängigkeiten auf den aktuellen Stand gebracht.** Gradle 9.7.0, Flyway 13.2.0, ArchUnit 1.5.0, Playwright 1.62.0, Checkstyle 13.9.0, OWASP Dependency-Check 13.0.0 und die übrigen Werkzeuge; die PostgreSQL-, Tomcat- und Netty-Pins ziehen auf den jeweils neuesten Patch nach. Rein intern, keine Auswirkung auf die Oberfläche.

- **Keycloak auf 26.7.1 angehoben (Sicherheitsupdate).** Das Patch-Release schließt fünf Schwachstellen im Anmeldedienst — unter anderem eine Umgehung der Signaturprüfung bei verschlüsselten OIDC-Request-Objects, eine Rechte-Eskalation über die Client-Verwaltung und drei Lücken in der feingranularen Rechteverwaltung. Keine Funktions- oder Konfigurationsänderung: Das gepinnte Container-Image (`quay.io/keycloak/keycloak:26.7`-Digest, weiterhin JDK 21) und die SPI-Artefakte des `keycloak-spi`-Moduls ziehen mit. **Deploy-Hinweis:** Der Keycloak-Container muss dafür neu gestartet werden.

- **Build- und Testwerkzeuge auf den aktuellen Stand gebracht:** CycloneDX 3.4.1, Flyway 13.3.0, OkHttp 5.5.0, axe-core 4.13.0, Spotless 8.10.0 und JUnit 6.1.3. MapStruct bleibt bewusst auf 1.6.3 — für 1.7.0 gibt es bisher nur Beta-Builds. Rein intern, keine Auswirkung auf die Oberfläche.

### Fixed

- **Alarm-Mails wiederholen sich nicht mehr stündlich bzw. alle vier Stunden.** Alertmanager kennt kein „zur Kenntnis genommen" und schickte dieselbe Meldung erneut, solange der Alarm anlag — bei einem Alarm, der einen Zustand prüft, also endlos. Pro Ereignis kommt jetzt eine Mail und, sobald es vorbei ist, eine Entwarnung; das stündliche Nachfassen bei kritischen Alarmen läuft weiter über Discord. **Deploy-Hinweis:** Der Alertmanager-Container muss dafür neu erzeugt werden (neue Option `--data.retention=744h`).

- **Fehlalarm „External sync stale" nach jedem Backend-Neustart behoben.** Der Zeitstempel des letzten erfolgreichen Laufs eines Hintergrundjobs wurde bereits beim *Start* eines Laufs mit dem Platzhalter `0` veröffentlicht; die Überwachung las das als „zuletzt erfolgreich am 01.01.1970" und schlug an, solange der erste Lauf nach einem Neustart dauerte — beim SC-Wiki-Abgleich 10 bis 15 Minuten. Der Zeitstempel entsteht jetzt erst mit dem ersten Erfolg, und die sechs betroffenen Alarmregeln ignorieren den Platzhalter zusätzlich selbst.

- **Fehlalarm „Audit domain silent 14d (ROLE)" behoben.** Die Überwachung meldete jede Audit-Domäne als verdächtig still, die 14 Tage lang nichts aufgezeichnet hat — für den Bereich Rollen & Mitglieder ist das aber eine gewöhnliche ruhige Phase, und da der Alarm einen Zustand und kein Ereignis prüft, wiederholte er sich alle vier Stunden per Mail, bis jemand eine Rolle änderte. Rollen & Mitglieder ist jetzt von der Regel ausgenommen — wie Beförderung, Mein Inventar und Materialbörse, die dort naturgemäß wochenlang still sind. Das Betriebs-Dashboard zeigt das Audit-Volumen dieser Bereiche stattdessen in einer 60-Tage-Tabelle.

- **Das „+ Zuordnen"-Popover im Lager bleibt immer vollständig im Bild.** Klappte es nach oben auf, obwohl darüber zu wenig Platz war, ragte sein oberer Teil — im Auswahlmodus die Auftragsliste — aus dem sichtbaren Bereich und war nicht erreichbar, weil sich ein fest positioniertes Element nicht heranscrollen lässt. Es klappt jetzt nur noch nach oben, wenn es dort auch hineinpasst, und wird andernfalls in den sichtbaren Bereich gerückt.

- **Ein kurzer Aussetzer der Container-Registry löst keinen Sicherheitsalarm mehr aus.** Die Signaturprüfung vor dem Deploy wird jetzt bis zu dreimal wiederholt und schreibt die tatsächliche Fehlermeldung ins Log, statt sie zu verwerfen. Bisher war ein Netzwerk-Schluckauf nicht von einem manipulierten Image zu unterscheiden und brach den Deploy als kritischen Alarm ab. Neue Schalter: `IRI_COSIGN_VERIFY_ATTEMPTS` (Standard 3) und `IRI_COSIGN_VERIFY_DELAY` (Standard 5 s).

### Security

- **Die Signaturprüfung vor dem Deploy läuft künftig mit cosign 3.1.3.** Das Update schließt eine Schwachstelle (GHSA-fx35-mq7g-6g98), bei der cosign die Prüfung der Signatur-Identität stillschweigend übergehen konnte. Unsere Prüfung der Container-Images war davon nicht betroffen — die Lücke greift nur bei der Prüfung einzelner Dateien (`verify-blob`). Reine Runbook-Änderung, keine Code-Änderung; das Programm auf dem Server wird beim nächsten Wartungsfenster ausgetauscht.

## [v1.5.41](https://github.com/krt-profit/basetool/releases/tag/v1.5.41) - 2026-08-05

### Changed

- **Der Footer ist auf jeder Seite eine Zeile flacher.** Das „Made By The Community"-Logo samt Markenhinweis von Cloud Imperium steht jetzt am Ende der Startseite statt im fest eingeblendeten Footer — beides sind laut Star-Citizen-Fankit zulässige Platzierungen. Das gibt vor allem auf Handy und Tablet spürbar Platz zurück.

## [v1.5.40](https://github.com/krt-profit/basetool/releases/tag/v1.5.40) - 2026-08-05

### Added

- **Der Materialbedarf lässt sich jetzt filtern und sortieren.** Hinter einem **Filter**-Knopf (einklappbar wie im Lager) lässt sich nach Material — mit Suchfeld — und Qualitätsstufe eingrenzen, und bereits gedeckte Materialien lassen sich ausblenden. Die Spalten Material, Bedarf, Bestand, Eintragungen und Offen sind per Klick auf die Überschrift sortierbar. Die Zeile „Basis: N Aufträge" entfällt.

## [v1.5.39](https://github.com/krt-profit/basetool/releases/tag/v1.5.39) - 2026-08-05

### Added

- **Neue Seite „Materialbedarf" in der Auftragsverwaltung.** Sie zeigt, wie viel von jedem Material über alle offenen und in Bearbeitung befindlichen Aufträge hinweg noch zu beschaffen ist — getrennt nach bearbeitender Einheit, mit Bedarf, bereits zugeordnetem Bestand, Eintragungen und der verbleibenden Lücke. Material- und Item-Aufträge zählen beide mit; jede Zeile lässt sich zu den Aufträgen aufklappen, aus denen sie sich zusammensetzt.

### Changed

- **Die Filterzeile im globalen Lager lässt sich jetzt einklappen — wie in „Mein Lager".** Sie steht hinter einem **Filter**-Knopf in der Aktionsleiste und schiebt die Tabelle nicht mehr nach unten. Ist etwas gefiltert, zeigt der Knopf die Anzahl der aktiven Filter, damit eine kurze Tabelle nie unerklärt bleibt. Die Einstellung merkt sich der Browser; ohne gespeicherte Wahl startet das Panel nur dann zugeklappt, wenn nichts gefiltert ist.

- **Die Beschriftungen „Material" und „Qualität ≥" im globalen Lager sind jetzt übersetzt.** Sie standen fest auf Deutsch und blieben auch in der englischen Oberfläche stehen.

## [v1.5.38](https://github.com/krt-profit/basetool/releases/tag/v1.5.38) - 2026-08-04

## [v1.5.37](https://github.com/krt-profit/basetool/releases/tag/v1.5.37) - 2026-08-04

### Fixed

- **Das technische Konto des Gateways lässt sich jetzt aus der Mitgliederliste entfernen.** Der Eintrag stand dort als „Nicht in Keycloak", ließ sich aber nicht löschen: die Sicherheitsabfrage vor dem Löschen fand ihn in Keycloak sehr wohl. Für technische Konten gilt sie jetzt nicht mehr — für Mitglieder unverändert. Der erste Anlauf in v1.5.37 endete stattdessen in einem allgemeinen Fehler, weil die Prüfung eine Berechtigung brauchte, die das Basetool in Keycloak nicht hat; sie kommt jetzt ohne aus.

- **Wenn ein Aufruf an Keycloak scheitert, sagt das Basetool das jetzt.** Solche Fehler kamen als „Ein unerwarteter Fehler ist aufgetreten" an — eine Meldung, die weder den betroffenen Dienst nennt noch von einem echten Programmfehler zu unterscheiden ist. Sie werden jetzt als Störung eines fremden Dienstes ausgewiesen.

## [v1.5.36](https://github.com/krt-profit/basetool/releases/tag/v1.5.36) - 2026-08-04

### Fixed

- **Der Versand aus dem Extractor wird wieder dir zugerechnet — mit deinen Rechten und deiner Zustimmung.** Das Backend prüfte Freigabe, Zustimmung und Rechte am technischen Konto des Gateways statt an der sendenden Person. Für die Dauer eines solchen Aufrufs gilt jetzt wieder deine Identität; ein deaktiviertes Mitglied kann darüber nichts mehr senden.

- **Ein in der Benutzerverwaltung deaktiviertes Mitglied kann über den Extractor nichts mehr senden.** Bisher wirkte eine Deaktivierung dort erst, wenn das Konto tatsächlich gelöscht wurde. Sie greift jetzt beim nächsten Abgleich, eine Reaktivierung entsprechend bei der nächsten Anmeldung.

- **Eine fehlgeschlagene Zustimmung gilt nicht mehr als erteilt.** Die Zustimmung wurde vorgemerkt, bevor sie gespeichert war — schlug das Speichern fehl, galt sie bis zum nächsten Neustart trotzdem als erteilt. Jetzt zählt sie erst, wenn sie wirklich gespeichert ist.

- **Das Gateway erscheint nicht mehr als neues Mitglied.** Sein technisches Konto löste bisher eine Registrierung samt Freigabeanfrage an die Administratoren aus, weil ein solches Konto von einer Person nicht zu unterscheiden war. Es wird jetzt als Maschine erkannt.

## [v1.5.35](https://github.com/krt-profit/basetool/releases/tag/v1.5.35) - 2026-08-04

### Fixed

- **Der Versand aus dem Extractor funktioniert jetzt tatsächlich.** Die Korrektur in v1.5.34 griff noch nicht: das Gateway prüfte das Zertifikat des Anmeldeservers zwar nicht mehr gegen den Truststore des Backends, dafür aber gegen den eines intern erreichbaren Anmeldeservers — mit demselben Ergebnis. Es akzeptiert jetzt beide Herkünfte, sodass die Prüfung unabhängig davon aufgeht, welche Adresse konfiguriert ist.

## [v1.5.34](https://github.com/krt-profit/basetool/releases/tag/v1.5.34) - 2026-08-04

### Fixed

- **Der Versand aus dem Extractor scheitert nicht mehr an einer falschen Vertrauensstellung.** Das Gateway prüfte das Zertifikat des Anmeldeservers gegen den Truststore des Backends und konnte deshalb gar keine Verbindung aufbauen — sichtbar nur als „An unexpected error occurred.". Es nutzt jetzt die passenden Wurzelzertifikate.

- **Ein Gateway ohne nutzbare Anmeldung sagt das jetzt.** Statt eines nichtssagenden Serverfehlers kommt eine benannte Meldung mit dem Hinweis, dass es am Server liegt und nicht am eigenen Export.

## [v1.5.33](https://github.com/krt-profit/basetool/releases/tag/v1.5.33) - 2026-08-04

### Changed

- **Der Alarm zur Zustimmungseinführung schlägt nur noch an, wenn tatsächlich mehrere Personen ausgesperrt sind.** Bisher zählte er abgelehnte Anfragen statt Personen — ein einzelnes wiederholt anfragendes Programm genügte, um ihn nachts auszulösen, obwohl niemand betroffen war. Er beobachtet jetzt, wie viele **verschiedene** Mitglieder abgelehnt werden, und lässt einer Person auch genug Zeit, die Bedingungen in Ruhe zu lesen.

- **Der Desktop-Extractor sendet wieder und ist dabei besser geschützt als zuvor.** Seit dem 3. August schlug jeder Versand mit „A valid bearer token is required" fehl: das Zugangs-Token war an den Rechner gebunden, wurde aber in einer Form übergeben, die der Server aus Sicherheitsgründen ablehnt. Das Gateway prüft die Bindung jetzt selbst und spricht mit dem Backend unter eigener Kennung weiter — die Bindung wirkt damit genau dort, wo sie zählt, nämlich auf der Strecke aus dem Internet (ADR-0129).

### Fixed

- **Ein offener Tab läuft nach einer Änderung der Nutzungsbedingungen nicht mehr endlos ins Leere.** Die Benachrichtigungsverbindung eines Tabs, der beim Wirksamwerden neuer Bedingungen offen war, wurde auf die Zustimmungsseite umgeleitet, scheiterte daran und baute sich sofort neu auf — dauerhaft und unbemerkt. Sie wird jetzt einmal gezielt beendet, und der Tab wechselt auf die Zustimmungsseite (REQ-SEC-028).

- **Die Glocke friert hinter der Zustimmungspflicht nicht mehr stumm ein.** Der Zähler für ungelesene Benachrichtigungen und die Einträge im Aufklappmenü verwarfen die Antwort des Zustimmungs-Gates kommentarlos und zeigten weiter den letzten Stand. Sie leiten jetzt wie jede andere Aktion auf die Zustimmungsseite weiter.

- **P4K-Import (Admin): Die laufende Fortschrittsabfrage erkennt jetzt die Zustimmungsabfrage, statt sie als Erfolg zu lesen.** Wurde während eines Imports eine neue Fassung der Nutzungsbedingungen aktiv, holte die Seite alle 3 Sekunden endlos die Zustimmungsseite, ohne dass sichtbar etwas passierte. Sie wechselt jetzt zur Zustimmungsseite und beendet die Abfrage — wie jede andere Oberfläche (REQ-SEC-028).

- **Deutlich weniger Serverlast, solange jemand noch nicht zugestimmt hat.** Das Zwischenergebnis „hat noch nicht zugestimmt" wurde gespeichert, aber nie wiederverwendet, sodass jede einzelne Anfrage einer nicht zugestimmten Sitzung eine zusätzliche Abfrage im Hintergrund auslöste. Es gilt jetzt wie das positive Gegenstück bis zu 60 Sekunden; eine Zustimmung wirkt weiterhin sofort.

- **Live-Aktualisierung hängt sich nach einer Änderung der Nutzungsbedingungen nicht mehr in endlose Verbindungsversuche.** Ein Tab, dessen Nutzer den geltenden Bedingungen noch nicht zugestimmt hatte, wurde bei der Live-Sync-Verbindung auf die Zustimmungsseite umgeleitet — was eine WebSocket-Verbindung nicht auswerten kann, weshalb sie unbegrenzt alle 30 Sekunden neu aufgebaut wurde. Die Verbindung wird jetzt einmalig endgültig beendet und der Tab wechselt auf die Zustimmungsseite (REQ-SEC-028).

- **Deployment: `IRI_MONITORING_ENABLED` wirkt jetzt auch, wenn es in der `.env` steht.** Der Deploy-Dienst liest diese Datei nicht ein, meldete deshalb bei jedem Lauf `IRI_MONITORING_ENABLED != 'true'` und spielte geänderte Monitoring-Konfiguration zwar auf die Platte, lud sie aber nie in das laufende Prometheus — korrigierte Alarmregeln feuerten dadurch weiter in ihrer alten Fassung. Ein explizit gesetzter Wert hat weiterhin Vorrang, und es wird ausschließlich dieser eine Schlüssel gelesen.

## [v1.5.32](https://github.com/krt-profit/basetool/releases/tag/v1.5.32) - 2026-08-03

## [v1.5.31](https://github.com/krt-profit/basetool/releases/tag/v1.5.31) - 2026-08-03

### Added

- **Raffinerieauftrag-Detailseite: Speichern und Einlagern bleiben jetzt auf der Seite, und zwei Personen sehen die Änderungen des anderen live.** Bisher sprang die Seite nach jeder Aktion zurück zur Liste, und ein zweiter Betrachter merkte von einer fremden Änderung nur den Versionskonflikt beim eigenen Speichern. Formular, Materialtabelle und der Einlagern-Dialog werden jetzt an Ort und Stelle aktualisiert; wird die Ausbeute einem Auftrag zugeordnet, frischt sich dessen offene Materialübersicht mit auf. Abbrechen führt weiterhin zur Liste, da der Auftrag die Arbeitsliste verlässt (REQ-FE-001/REQ-FE-015, #1238).

- **Blueprint-Import: Munition aus einem deutschen Star-Citizen-Client wird jetzt direkt erkannt.** Ein deutscher Client schreibt „(30 Schuss)" statt „(30 cap)"; solche Namen mussten bisher beim ersten Import einmal von Hand zugeordnet werden — rund 13 Stück. Die Zuordnungen sind jetzt vorbelegt. Wer sie bereits selbst zugeordnet hat, behält seine Zuordnung (#1485).

- **Live-Aktualisierung jetzt auch in Missionsliste, Raffinerie, Mitgliederverwaltung und Organisationsstruktur.** Ändert jemand anderes einen Eintrag, aktualisiert sich die offene Liste an Ort und Stelle statt still zu veralten — bisher half nur ein manuelles Neuladen. Das Organigramm und der Struktur-Editor teilen sich dabei einen Kanal: eine Zuordnungsänderung erreicht beide Ansichten (REQ-FE-015).

- **Einlagern aus der Raffinerie aktualisiert jetzt auch ein offenes Lager.** Die eingelagerte Ausbeute erschien dort bisher erst nach einem Neuladen.

- **Zwei neue Alarme für die Live-Aktualisierung.** Verwirft die Verteilung eine Änderung, sehen die anderen Betrachter unbemerkt einen veralteten Stand — ohne Fehlermeldung auf beiden Seiten. `LiveSyncRelayDropsSustained` schlägt bei anhaltenden Verlusten je Oberfläche an, `LiveSyncSectionKeySkew` beim Senden eines Bereichsschlüssels, den die Verteilung nicht kennt. Beide Schwellen stammen aus 21 Tagen gemessenem Produktivbetrieb (REQ-OBS-011).

- **Neue Kennzahl `basetool_livesync_peer_rooms` samt Panel im Betriebs-Dashboard.** Sie zeigt je Oberfläche, in wie vielen Räumen mehrere Personen gleichzeitig sind. Ohne sie ist ein Ausbleiben von Aktualisierungen nicht davon zu unterscheiden, dass schlicht niemand gemeinsam bearbeitet hat.

- **Nutzungsbedingungen müssen jetzt bestätigt werden — auch für den Extractor.** Ohne Zustimmung ist keine Nutzung möglich; bei Ablehnung wird man abgemeldet und kann sich jederzeit erneut anmelden und zustimmen. Ändert sich der Text, erscheint die Seite erneut, und wer wann welcher Fassung zugestimmt hat, ist nachvollziehbar gespeichert (REQ-SEC-028, ADR-0127).

- **Neue Adminübersicht „Zustimmung zu den Nutzungsbedingungen“.** Zeigt, wer der geltenden Fassung zugestimmt hat und wer noch nicht, voreingestellt auf die offenen Fälle. Ohne sie wäre nach einer Textänderung nicht zu unterscheiden, ob sich schlicht noch niemand angemeldet hat oder ob die Zustimmung gar nicht funktioniert.

- **Nutzungsbedingungen: Nur vom Betreiber freigegebene Client-Software darf die Schnittstellen nutzen.** Bisher stand diese Regel nur in der Entwicklerdokumentation und war damit nicht Vertragsbestandteil — ein Sperrgrund ließ sich nur über die Klausel „ohne Angabe von Gründen" stützen. Abschnitt 4 nennt sie jetzt ausdrücklich, für alle Schnittstellen und einschließlich Entwicklung und Bereitstellung fremder Clients (REQ-SEC-027, Stand 03.08.2026).

### Changed

- **Sicherheit: Die JWT-Audience-Prüfung des Backends läuft jetzt in jedem E2E-Durchlauf scharf.** Bisher war sie nirgends aktiv — die Produktion wäre der erste Ort gewesen, an dem der Prüfpfad überhaupt ausgeführt wird, und dort weist eine fehlende `aud` jede Anmeldung ab. Der E2E-Realm schreibt die Audience jetzt in seine Tokens, der E2E-Stack prüft sie, und ein Paritätstest verhindert, dass beides auseinanderläuft (Audit L-1, REQ-SEC-024). In Produktion bleibt die Prüfung unverändert aus.

- **Ingest-Gateway: DPoP schützt jetzt den dauerhaft gespeicherten Refresh-Token statt des Access-Tokens.** Die ursprüngliche Variante konnte nicht funktionieren: das Gateway reicht das Token an das Backend weiter, und ein schlüsselgebundenes Token übersteht diesen zweiten Schritt nicht — es hätte den Schutz genau dort verloren, wo er greifen soll. Für Nutzer ändert sich nichts (REQ-INGEST-012).

- **Die Bearbeitungs-Anzeige auf der Missionsseite gilt jetzt über alle Serverinstanzen hinweg.** Der Hinweis „wird gerade bearbeitet von“ erschien bisher nur, wenn beide Bearbeiter zufällig von derselben Instanz bedient wurden; bei mehreren Instanzen fehlte er, obwohl die Änderungen selbst korrekt ankamen. Die Anzeige wird jetzt zwischen den Instanzen abgeglichen — fällt Redis aus, verhält sie sich wie bisher instanzlokal (ADR-0126, neuer Kanal `basetool:livesync:presence`, einstellbar über `APP_LIVESYNC_REDIS_PRESENCE_CHANNEL`).

- **Betriebsmittel des Servers nach einer Messwoche neu zugeschnitten.** Die Datenbanken waren für einen Datenbestand ausgelegt, den es nicht gibt: 2 GB Speicherlimit und ein 512 MB großer Puffer für eine 108 MB kleine Datenbank. Beide Datenbank-Container und ihre Postgres-Einstellungen sind jetzt an den gemessenen Bedarf angepasst — das gibt 768 MB frei, ohne dass irgendwo weniger zur Verfügung steht als benötigt (ADR-0085, #937).

- **Vier Dienste dürfen jetzt kurzzeitig mehr Rechenleistung ziehen.** Die Weboberfläche stand pro Woche rund 25 Minuten still, weil ihre Obergrenze kurze Lastspitzen abschnitt — bei einem Server, der im Mittel zu 3 % ausgelastet ist. Betroffen waren Weboberfläche, Ingest-Gateway, Sitzungsspeicher und der vorgelagerte Webserver; spürbar wird das als geringere Wartezeit beim Seitenaufbau (#937).

- **Organisationsstruktur: Die Seite lädt nach dem Anlegen oder Umhängen einer Einheit nicht mehr komplett neu.** Nur der betroffene Abschnitt wird neu gezeichnet; eine halb ausgefüllte Anlegen-Maske bleibt erhalten und wird erst auf Klick aktualisiert.

### Fixed

- **Sicherheit: Ein noch nicht freigeschaltetes Konto konnte die API über einen kodierten Pfad erreichen.** Die Sperre für Konten, die auf die Freischaltung warten, verglich den rohen Adresspfad, die Weiterleitung dagegen den dekodierten — eine Anfrage auf `/%61pi/...` kam so an der Sperre vorbei und wurde trotzdem an den Endpunkt zugestellt. Beide vergleichen jetzt denselben dekodierten Pfad (REQ-SEC-017).

- **Sicherheit: Mehrere Schutzfilter ließen sich durch einen kodierten Pfad umgehen.** Die Filter prüften den rohen Adresspfad, die Weiterleitung dagegen den dekodierten — eine Anfrage auf `/%761/...` statt `/v1/...` kam so an ihnen vorbei und landete trotzdem beim Endpunkt. Betroffen waren am Ingest-Gateway die Client-Prüfung, die Größenbegrenzung, die Ratenbegrenzung und das Zugriffsprotokoll, im Backend die Größenbegrenzung für JSON-Importe und die Cache-Vorgaben der API-Antworten. Alle prüfen jetzt denselben dekodierten Pfad (REQ-SEC-029, REQ-INGEST-011).

- **Die API-Dokumentation schrieb sich bei jedem Build selbst um.** `openapi.json` enthielt drei interne Prüfregeln des Lager-Formulars und zwei der Bank als vermeintliche Eingabefelder; deren Reihenfolge war zufällig, wodurch jeder Build die Datei veränderte, ohne dass sich an der Schnittstelle etwas geändert hatte. Die Pseudo-Felder sind entfernt, die Datei ist jetzt reproduzierbar, und ein Test hält das so (REQ-API-007).

- **Fehlalarm „ExternalFetchErrors“ für den UEX-Katalog behoben.** Zwei dauerhaft leere UEX-Kategorien (Jumpsuits, Consumable) wurden bei jedem Abgleich als Abruffehler gezählt, weil UEX ein leeres Ergebnis als `data: null` ausliefert — zusammen mit Backend-Neustarts löste dieser Dauerzähler den Alarm aus, ohne dass eine Störung vorlag. Gezählt wird jetzt nur noch, was UEX selbst als Fehler meldet (REQ-OBS-011).

- **Ingest-Gateway: Blueprint-Versand aus dem Extractor schlug fehl.** Der Extractor schreibt je Export-Pfad eine andere Herkunftsangabe; die Freigabeliste kannte nur eine davon, wodurch jeder Blueprint-Versand mit „nicht freigegeben" abgewiesen wurde. Beide Schreibweisen sind jetzt dokumentiert, und der Vergleich ignoriert Groß-/Kleinschreibung.

- **Ingest-Gateway: Eine Ablehnung nennt jetzt die Ursache.** Alle vier Prüfungen antworteten mit demselben Satz, sodass aus der Meldung nicht hervorging, welche gegriffen hat. Zusätzlich wird jede abgelehnte Anmeldung nach Fehlerart gezählt — ein 401 war bisher im Betrieb nicht auswertbar.

- **Die Formatierungsprüfung des Frontends schlug auf Windows-Arbeitsplätzen grundlos fehl.** Für TypeScript-Deklarationsdateien fehlte die Zeilenenden-Regel, sodass ein frischer Checkout unter Windows drei Dateien mit CRLF anlegte und Prettier sie beanstandete, obwohl niemand sie angefasst hatte — auf dem Linux-Server blieb der Fehler unsichtbar. `.gitattributes` deckt jetzt auch `*.ts` ab; die Dateiinhalte bleiben unverändert (ADR-0125).

- **Auf jeder Einsatz-Detailseite stand dauerhaft eine rote Fehlermeldung.** Der Hinweis „Der Abschnitt konnte nicht aktualisiert werden" ist nur für eine fehlgeschlagene Teil-Aktualisierung gedacht, wurde aber unter den Reitern bei jedem normalen Seitenaufruf angezeigt — obwohl nichts fehlgeschlagen war. Er erscheint jetzt nur noch im Fehlerfall (REQ-FE-005).

## [v1.5.30](https://github.com/krt-profit/basetool/releases/tag/v1.5.30) - 2026-08-03

### Added

- **Ingest-Gateway: Die Schnittstelle ist jetzt auf freigegebene Clients beschränkt.** Bisher wurde jedes gültige Token aus dem Realm akzeptiert — auch eines der Weboberfläche. Das Gateway prüft jetzt Client-Kennung, Berechtigungsumfang und die Herkunftsangabe der Nutzdaten gegen eine Freigabeliste und antwortet sonst mit `403 CLIENT_NOT_ALLOWED`. Wer den Extraktor nutzen darf, ändert sich nicht: jedes Mitglied darf Blueprints und Raffinerie-Aufträge hochladen (REQ-INGEST-011, ADR-0018).

- **Ingest-Gateway: Unterstützung für schlüsselgebundene Token (DPoP, RFC 9449).** Ein abgeflossenes Token ist damit ohne den zugehörigen privaten Schlüssel wertlos — relevant, weil der Extraktor sein Token dauerhaft auf dem Rechner speichert. Vorerst im Parallelbetrieb: einfache Bearer-Token funktionieren unverändert weiter, bis der Extraktor nachzieht (REQ-INGEST-012).

- **Neue Betriebskennzahlen für das Ingest-Gateway.** Gezählt werden jetzt die aufrufende Client-Software und jede Abweisung der Freigabeprüfung samt Grund. Der neue Alarm `IngestUnknownClient` unterscheidet dabei „ein fremdes Werkzeug ruft an" von „eine Keycloak-Zuordnung ist kaputt und sperrt den echten Extraktor aus".

### Changed

- **Ingest-Gateway: Zwei vom Client gelieferte Kopfzeilen wurden ungeprüft an das Backend weitergereicht.** `X-Correlation-Id` und `Accept-Language` wurden roh auf den internen Aufruf kopiert, obwohl das Gateway als einziger Dienst aus dem Internet erreichbar ist. Beide werden jetzt geprüft; die Korrelations-Kennung stammt aus der bereits validierten Quelle. Das behebt zugleich, dass ein Vorgang in Gateway- und Backend-Log unter verschiedenen Kennungen auftauchen konnte (REQ-OBS-002).

- **Raffinerie-Import: Quellbilder und Materialzeilen sind jetzt Pflicht.** Ein Auftrag ohne Zeilen enthält nichts zu importieren, und eine echte Auswertung entsteht immer aus mindestens einem Screenshot. Gateway und Backend prüfen identisch, sodass der Datei-Upload im Browser dieselbe Regel anwendet; vom Extraktor erzeugte Dateien sind nicht betroffen (ADR-0008).

- **Ingest-Gateway: Der Blueprint-Pfad protokolliert jetzt die Herkunft der Nutzdaten.** Bisher stand dort nur die Größe in Bytes, sodass ein strukturell auffälliger Export von einem normalen nicht zu unterscheiden war.

## [v1.5.29](https://github.com/krt-profit/basetool/releases/tag/v1.5.29) - 2026-08-02

### Added

- **Ingest-Gateway: Der API-Vertrag der beiden Ingest-Endpunkte ist jetzt als OpenAPI-Dokument veröffentlicht.** `ingest/src/main/resources/api/openapi.json` beschreibt Schemata, Statuscodes und die Bearer-Authentifizierung, gegen die der Desktop-Extraktor entwickelt wird — bisher gab es dafür nur den Quelltext. Wie beim Backend wird das Dokument aus dem Test erzeugt und ist in der Produktion nicht abrufbar (REQ-INGEST-010, REQ-API-007).

- **Fehler im Browser werden jetzt serverseitig erfasst.** Brach nach einer Teil-Aktualisierung ein Skript ab, sah der Nutzer nur ein totes Bedienfeld und im Server-Log stand nichts davon. Der Browser meldet solche Fehler jetzt an `POST /internal/client-error` (nur angemeldet, auf fünf Meldungen je Sitzung gedeckelt); übertragen werden ausschließlich Meldung, Quelle, Zeile, Spalte und Fehlerart — kein Stacktrace, keine Seiteninhalte, keine Formulareingaben.

- **Die Protokolle der Wartungsjobs und der Keycloak-Konsole sind jetzt in der Log-Auswertung lesbar.** Ausrollen, Sicherung, Aufräumen und Wiederherstellungsprobe schrieben ihre Ausgabe nur in Dateien auf dem Server — nach einer nächtlichen Alarmmail führte der einzige Weg zur Ursache über SSH. Jede betroffene Alarmmeldung nennt jetzt die passende Abfrage, und ein Wächter meldet, wenn einer der zehn überwachten Log-Kanäle verstummt.

- **Änderungen an Rollenberechtigungen stehen jetzt im Aktivitätsprotokoll.** Es war die letzte Mutation im Bereich „Rollen“, die keine Spur hinterließ. Der Eintrag nennt die hinzugefügten und entfernten Berechtigungen und ist im Protokoll-Reiter filterbar; ein unbekannter Wert wird dabei nur gezählt, nie benannt (REQ-AUDIT-001).

- **Lager: Die Filterleiste in „Mein Lager“ lässt sich jetzt einklappen.** Die Filterzeile lief über mehrere Zeilen und drängte die Tabelle nach unten; sie sitzt jetzt in einem einklappbaren Bereich, und die Wahl bleibt je Browser erhalten. Damit ein zugeklappter Bereich keinen aktiven Filter verbergen kann, zeigt der Schalter deren Anzahl (REQ-INV-037).

### Changed

- **Protokollierung: Eine abgewiesene Anfrage ist jetzt zuordenbar, Routine-Rauschen verschwindet.** Abweisungen wegen fehlender Freigabe nannten den Betroffenen nicht, Zeilen des Frontends trugen die Organisationseinheit nicht, und jeder Tastendruck in einem Suchfeld erzeugte bei einer Störung eine Warnung. Warnungen sind jetzt wieder Warnungen, Routinefälle liegen auf DEBUG, Client-IPs verschwinden aus dem Log, und ein Speicherkonflikt nennt die betroffene Zeile samt beider Versionsstände.

- **Eingaben aus Such- und Formularfeldern können keine Log-Zeilen mehr fälschen.** Ein eingefügter Zeilenumbruch mit gefälschtem Fehler-Präfix las sich bei der Auswertung wie eine echte Meldung. Solche Werte werden jetzt in allen drei Diensten bereinigt und gekürzt, bevor sie ins Log gelangen.

- **Ein stillgelegter Log-Kanal fällt jetzt auf, und ein Neustart schneidet das Log-Ende nicht mehr ab.** Konnte ein Ausgabekanal seine Datei nicht öffnen, schrieb er stillschweigend ins Nichts, während die Fehlerzählung unauffällig blieb. Solche Störungen melden sich jetzt selbst; beim Herunterfahren bleiben fünf statt einer Sekunde, um den Puffer zu leeren (REQ-OBS-017).

- **Benutzerabgleich meldet jetzt, was er getan hat.** Wie viele Konten neu als ausgeschieden markiert und wie viele Rollen mangels Entsprechung auf „Gast“ zurückgefallen sind, stand nirgends. Beides wird jetzt je Lauf zusammengefasst; auffällig viele Fälle erzeugen eine Warnung.

- **Zusätzliche Betriebskennzahlen.** Gezählt werden jetzt verdrängte Sitzungen an der Zehn-Sitzungen-Grenze, abgebrochene Benachrichtigungskanäle samt Ursache, verweigerte Live-Abonnements samt Grund und die gemeldeten Browser-Fehler. Drei neue Alarme werten sie aus: auffällig viele Browser-Fehler, dauerhaft verdrängte Sitzungen und ein SC-Wiki-Abgleich, der an drei Tagen in Folge unvollständig blieb.

- **Log-Level lassen sich jetzt im laufenden Betrieb umstellen.** Bisher kostete jede DEBUG-Diagnose eine Konfigurationsänderung samt Neustart — ausgerechnet die aussagekräftigsten Zeilen liegen aber bewusst auf DEBUG. Im Backend dürfen das nur Administratoren; Frontend und Ingest-Gateway geben ihre Stufen in der Produktion nur noch aus, weil ihr Wartungszugang keine Anmeldung kennt, und ein Neustart verwirft jede Änderung (REQ-OBS-016, ADR-0090).

- **Geplante Aufgaben sind im Log als ein Lauf erkennbar.** Die acht nächtlichen Jobs schrieben ohne Kennung; bei überlappenden Zeitplänen ließen sich ihre Zeilen nicht mehr auseinanderhalten. Jeder Lauf bekommt jetzt eine eigene Kennung aus Job-Name und Zufallssuffix.

- **Anmeldung über Discord: Eine Ablehnung nennt jetzt ihren Grund.** Zeitüberschreitung, DNS-Problem, Discord-Drosselung, Ausfall auf Discord-Seite oder unlesbare Antwort endeten alle in derselben nichtssagenden Meldung. Auch der stillschweigend übersprungene Dublettencheck beim Anlegen eines Kontos wird jetzt protokolliert.

- **Ingest-Gateway: Jede Ablehnung ist jetzt im Log nachvollziehbar.** Bisher blieben eine Drosselung, ein abgelehntes Extrakt und ein abgelaufenes Token ohne jede Spur — im Log stand nur der Statuscode. Protokolliert werden jetzt der ausgelöste Limiter, die verletzte Feldregel, beide Größen beim Größen-Limit und die Form des Extrakts; Nutzdaten, Namen, Token und Client-IPs bleiben außen vor.

- **Ingest-Gateway: 401 und 403 liefern jetzt eine auswertbare Fehlermeldung.** Beide antworteten bisher mit leerem Rumpf; der Extraktor konnte "Token erneuern" nicht von "nicht berechtigt" unterscheiden. Sie tragen jetzt dieselbe Fehlerstruktur wie alle übrigen Antworten des Gateways (REQ-API-004).

- **Ingest-Gateway: Ist die Zwischenablage nicht erreichbar, kommt eine Wiederholen-Antwort statt eines Serverfehlers.** Fiel Redis aus, meldete das Gateway einen 500er und schrieb einen Fehler-Stacktrace ins Log, obwohl die Übergabe ans Backend bereits geglückt war. Jetzt gibt es einen 503 mit Wartezeit, eine Warnung statt eines Fehlers und einen eigenen Alarm, der auf Redis zeigt statt aufs Backend (REQ-INGEST-003).

- **Ingest-Gateway: Protokollierung auf dem Stand von Backend und Frontend.** Jede Zeile trägt jetzt zusätzlich den Nutzer (Keycloak-`sub`, nie Name oder E-Mail), langsame Anfragen werden wie in den anderen Modulen als Warnung protokolliert, und jede Weiterleitung ans Backend hinterlässt eine Zeile mit Dauer. Neu einstellbar über `APP_LOGGING_*` (REQ-OBS-001/-002/-003).

- **Die Browser-Skripte werden jetzt statisch typgeprüft.** Der TypeScript-Compiler läuft als reiner Prüfer (`tsc --noEmit`) über die Skripte unter `static/js` und hängt als `:frontend:typecheckJs` streng im `check`-Gate. Der Quellcode bleibt JavaScript — es wird nichts kompiliert, gebundelt oder umbenannt; Dateien nehmen einzeln per `// @ts-check` teil (derzeit 27 von 87, darunter das gesamte gemeinsame Fundament). Die Backend-DTO-Typen werden bei jedem Build aus `openapi.json` erzeugt, statt im Frontend von Hand nachgebaut zu werden, womit eine Feldumbenennung im Backend beim Bauen auffällt statt erst zur Laufzeit (REQ-FE-018, ADR-0125).

### Fixed

- **Katalogabgleich: Ein unvollständiger Abruf löscht keine Einträge mehr.** Brach der Seitendurchlauf des SC-Wiki mittendrin ab oder fehlte die Seitenangabe, wertete der Abgleich den Rest des Katalogs als gelöscht und markierte ihn entsprechend. Ein unvollständiger Lauf übernimmt jetzt seine Zeilen, verzichtet aber auf das Aufräumen und meldet den Grund. Beim UEX-Abgleich bleibt zudem eine leere oder fehlerhafte Antwort nicht mehr unbemerkt, und ein unveränderter Katalog ist als solcher erkennbar statt als Nulllauf.

- **Live-Aktualisierung: Eine verweigerte Anmeldung an einem Raum bleibt nicht mehr unbemerkt.** Lehnte der Server das Abonnement ab, wirkte die Seite weiterhin aktuell, obwohl sie keine Peer-Änderungen mehr erhielt. Sie zeigt jetzt den Hinweis „Aktualisierungen verfügbar“, und war die Ablehnung nur eine vorübergehende Störung, versucht sie es genau einmal erneut.

- **Sicherheit: Netty auf 4.2.16.Final angehoben (u. a. CVE-2026-56820, CVE-2026-56819, CVE-2026-55833).** Die von Spring Boot vorgegebene Version 4.2.15.Final war über eine fehlende Zertifikatsprüfung im OCSP-Client (Umgehung der Sperrprüfung durch Replay), ein Speicherleck im HTTP/2-Codec sowie eine SPDY-Header-Dekodierung mit CPU-erschöpfendem Denial-of-Service angreifbar; die gepatchte Version wird jetzt erzwungen. Betroffen sind reale Laufzeitpfade (WebClient im Frontend, Redis-Anbindung über Lettuce). Die parallel gemeldete gleiche CVE-Reihe auf der Netty-4.1.x-Linie betrifft ausschließlich eine Nur-Kompilierzeit-Abhängigkeit des Keycloak-SPI-Moduls (vom Keycloak-Container zur Laufzeit bereitgestellt) und wurde begründet unterdrückt.

- **Monitoring: blackbox-Exporter-Speicherlimit auf 64 MB angehoben (`GOMEMLIMIT` 44 MiB).** Der Probe-Exporter lief seit dem 31.07. dauerhaft auf 97-100 % seines 32-MB-Limits und löste den `ContainerWorkingSetHigh`-Alarm aus, obwohl er nur 9,8 MiB Heap belegt. Ohne `GOMEMLIMIT` kannte die Go-Laufzeit das Container-Limit nicht und behielt den bei jeder Lastspitze angeforderten Speicher dauerhaft; das Limit selbst war seit dem Aufbau des Stacks unverändert, während die Zahl der Probe-Ziele auf 23 gewachsen ist. Greift beim nächsten Deploy.

- **Monitoring: Alle Go-Dienste des Überwachungs-Stacks haben jetzt vorsorglich eine Speicher-Obergrenze (`GOMEMLIMIT`).** Bisher hatten nur Prometheus und Alloy eine; ohne sie kennt die Go-Laufzeit das Container-Limit nicht und kann angeforderten Speicher dauerhaft behalten. Grafana, Loki, Tempo, cAdvisor, Alertmanager, node-exporter, beide Postgres-Exporter und der Redis-Exporter sind jetzt auf 75 % ihres Limits begrenzt — ohne zusätzlichen Speicherbedarf. Greift beim nächsten Deploy.

- **Monitoring: Der Speicheralarm bewertete rund die Hälfte der Meldungen falsch.** `ContainerWorkingSetHigh` misst auch die eingeblendete Programmdatei eines Dienstes mit — die ist aber jederzeit verdrängbar und kann keinen Speichermangel auslösen. Eine Nachmessung aller Container zeigte: bei Alertmanager, Alloy, node-exporter und den Exportern bestand bis zur Hälfte des gemeldeten Werts daraus; tatsächlich knapp war einzig der blackbox-Exporter. Alloys Anstieg vom 31.07. war kein Speicherleck, sondern eine Umbuchung nach dem Image-Wechsel auf Version 1.18.0. Der Alarm wertet jetzt nur noch den nicht verdrängbaren Anteil aus und heißt `ContainerMemoryHigh` (vorher `ContainerWorkingSetHigh`); Messanleitung, ADR-0085 und die Spec halten die Herleitung fest.

## [v1.5.28](https://github.com/krt-profit/basetool/releases/tag/v1.5.28) - 2026-08-02

### Added

- **Lager: Markierte Einträge lassen sich jetzt auch gesammelt umbuchen.** Neben "Markierte ausbuchen" steht in "Mein Lager" ein "Markierte umbuchen" — dieselbe Auswahl wird verschoben statt ausgebucht: an einen anderen Ort bzw. Nutzer oder als persönlich bzw. ins gemeinsame Lager. Jeder markierte Eintrag wandert vollständig und nimmt seine Auftrags- und Einsatz-Marken mit. Einträge, die bereits am Ziel liegen, werden übersprungen und mitgezählt; die Meldung nennt beide Zahlen (REQ-INV-036, ADR-0124).

### Changed

- **Lager: Beim Um- und Ausbuchen entfällt die Herkunft-Eingabe, wenn es nichts zu entscheiden gibt.** Ist ein Eintrag genau einem Einsatz bzw. genau einem Auftrag zugeordnet und bleibt kein freier Rest, kann die Menge nur von dieser einen Marke kommen — das Feld wird jetzt mit der um- bzw. ausgebuchten Menge vorbefüllt, gesperrt und als automatisch gefüllt gekennzeichnet, statt das Absenden zu blockieren, bis der Wert von Hand nachgetragen wird. Es folgt jeder späteren Änderung der Menge. Gibt es mehrere Marken oder einen Rest, bleibt die Aufteilung wie bisher freie Wahl (REQ-INV-027).

### Fixed

- **Kompakte Schaltflächen in dichten Tabellen sind jetzt tatsächlich kompakt.** Die Varianten für Zeilen-Aktionen waren seit ihrer Einführung wirkungslos: eine später deklarierte Regel überschrieb sie, sodass jede "dichte" Schaltfläche in voller Größe erschien. Aktionsspalten in Lager, Kartellbank, Materialbörse, Einsätzen und Operationen werden dadurch spürbar schmaler — alle übrigen Schaltflächen behalten unverändert ihre 44px-Zielgröße (REQ-UI-009).

- **Lager: Der Mengen-Dialog einer Auftrags- oder Einsatz-Zuordnung ist wieder bedienbar.** Beim Anklicken eines Zuordnungs-Chips schrumpfte das Mengenfeld auf wenige Pixel, und "Entfernen" ragte rechts aus dem Kästchen heraus. Das Mengenfeld nimmt jetzt eine eigene Zeile über voller Breite ein, die Schaltflächen teilen sich die darunter (REQ-UI-011).

## [v1.5.27](https://github.com/krt-profit/basetool/releases/tag/v1.5.27) - 2026-07-31

### Fixed

- **Raffinerie: Ein ausgeblendeter Standort wird nicht mehr als Raffinerie angeboten.** Blendete ein Administrator einen Standort mit Raffinerie aus, verschwand er zwar aus dem Lagerort-Feld, blieb im selben Formular aber als Raffinerie wählbar — der Auftrag ließ sich anlegen, der Ertrag danach aber nirgends einbuchen. Bestehende Aufträge behalten ihren Standort und lassen sich weiterhin speichern (REQ-REFINERY-020).

- **Release-Pipeline: Ein einzelner Runner ohne Verbindung zu Docker Hub bricht den Image-Bau nicht mehr ab.** Wiederholversuche allein halfen nicht, wenn die Verbindung über das gesamte Zeitfenster gestört blieb — genau daran scheiterte der Backend-Build von v1.5.26, während ein 22 Sekunden früher gestarteter Lauf alle Jobs bestand. Das BuildKit-Image wird jetzt ersatzweise über einen Spiegel-Server auf einem anderen Netzweg geladen. Ohne den Fehlversuch fehlten der Version alle Versionskennzeichen und Signaturen, sodass sie nicht auf den Server ausgerollt werden konnte.

- **Release-Pipeline: Ein fehlgeschlagener Image-Bau meldet nicht mehr zusätzlich einen irreführenden zweiten Fehler.** Der Upload des Sicherheits-Scans lief auch dann noch an, wenn der Build gar nicht erst zustande kam, und beschwerte sich über die fehlende Ergebnisdatei — was die eigentliche Ursache überdeckte.

- **Lager: Beim Einlagern stehen wieder alle Orte zur Auswahl.** Die Ort-Auswahl lud nur die ersten 25 Orte und zeigte keinen Hinweis, dass die Liste abgeschnitten war — 28 der 53 sichtbaren Orte waren dadurch nicht wählbar, darunter MIC-L5, Patch City, New Babbage und Orison. Der Ortskatalog wird jetzt vollständig geladen (REQ-FE-016).

- **Such-Auswahlfelder melden jetzt, wenn es mehr Treffer gibt.** Material-, Item- und Kontoauswahl luden genau so viele Zeilen, wie sie anzeigen können, wodurch der Hinweis „Weiter tippen, um die Liste einzugrenzen" technisch nie erscheinen konnte. Ab dem 51. Treffer wird er wieder angezeigt (REQ-FE-016).

## [v1.5.26](https://github.com/krt-profit/basetool/releases/tag/v1.5.26) - 2026-07-31

### Changed

- **Benutzerverwaltung: Beim Löschen eines Nutzers werden seine Daten jetzt wirklich gelöscht.** Lager, Hangar, "Mein Inventar", Baupläne, Benachrichtigungen und Bewertungen des Kontos werden entfernt statt auf einen Administrator umgebucht oder stillschweigend liegen gelassen; die Verknüpfungen der Lagereinträge zu Aufträgen und Einsätzen gehen mit. Einsätze, Operationen, Auftragshistorie und die Kartellbank bleiben erhalten — dort erscheint der Betroffene als "Gelöschter Nutzer" statt als leeres Feld. Raffinerieaufträge und von ihm angelegte Einsätze gehen weiterhin an einen Administrator über. Der Bestätigungsdialog beschreibt das jetzt zutreffend; bisher versprach er, alle Daten würden übertragen (REQ-DATA-008, REQ-AUDIT-001).

- **Bereits verwaiste Daten früherer Löschungen werden einmalig entfernt.** Baupläne, "Mein Inventar", Benachrichtigungen, Benachrichtigungsregel-Ziele und Bewertungen ohne zugehöriges Konto verschwinden mit der Migration. Ein solches Regel-Ziel legte bis dahin bei jedem passenden Ereignis eine neue Benachrichtigung für ein nicht mehr existierendes Konto an.

### Fixed

- **Operationen: Eine bereits abgerechnete Operation ändert sich nicht mehr rückwirkend, wenn ein Mitglied gelöscht wird.** Der Gelöschte fiel aus der Aufteilung, seine Anwesenheit zählte nicht mehr zur Gesamtdauer, und von ihm verauslagte Ausgaben wurden auf die übrigen Teilnehmer umverteilt — bei Operationen, deren Auszahlung längst feststand. Er bleibt jetzt mit seinem Anteil in der Aufstellung (REQ-DATA-008).

- **Benutzerverwaltung: Ein noch aktives Mitglied kann nicht mehr versehentlich gelöscht werden.** Ob ein Konto wirklich weg ist, wurde bisher nur am zuletzt gespeicherten Stand abgelesen — ein verschluckter Abgleichfehler genügte, um ein aktives Mitglied löschbar zu machen. Das wird jetzt direkt bei Keycloak nachgeprüft; ist Keycloak nicht erreichbar, wird das Löschen abgelehnt statt auf Verdacht ausgeführt (REQ-DATA-008).

- **Einsätze: Ein gelöschtes Mitglied wird nicht mehr als "Gast" ausgewiesen.** Die Zeile blieb namenlos und trug fälschlich das Gast-Kennzeichen, wodurch jeder Betrachter sie bearbeiten und löschen konnte. Sie zeigt jetzt "Gelöschter Nutzer" und ist nur noch für Einsatzberechtigte änderbar.

- **Benutzerverwaltung: Das Löschen eines Nutzers bricht nicht mehr mit einem Serverfehler ab.** Gehörte der Nutzer noch einer Staffel, einem Spezialkommando oder einem Bereich an — also praktisch immer —, endete das Löschen mit HTTP 500. Die Mitgliedschaftszeilen werden von der Datenbank mitgelöscht, hingen im selben Vorgang aber noch geladen im Speicher und zeigten dort auf den bereits entfernten Nutzer. Die Bank-Auswertung, die sie geladen hat, liest jetzt nur noch die IDs der betroffenen Einheiten (REQ-DATA-008, REQ-BANK-034).

## [v1.5.25](https://github.com/krt-profit/basetool/releases/tag/v1.5.25) - 2026-07-30

### Changed

- **Kartellbank: Der Kontoverantwortliche unterliegt auf seinem eigenen Konto keinem Freigabe-Limit mehr.** Staffelleiter, SK-Leiter und Bereichsleiter mussten ihre eigenen Auszahlungs- und Überweisungsanträge selbst freigeben, weil die Limit-Prüfung nicht danach fragte, wer den Antrag stellt — bei Konten ohne hinterlegtes Limit traf das jeden Antrag. Solche Anträge tragen jetzt kein „Über Limit" mehr und brauchen keine zusätzliche Freigabe. Am KRT-Konto gilt das ebenso für die Organisationsleitung; für alle anderen Antragsteller bleibt die Betragsstaffel unverändert. Bestätigt und gebucht wird ein Antrag weiterhin durch die Bank (REQ-BANK-041, REQ-BANK-047, ADR-0123).

## [v1.5.24](https://github.com/krt-profit/basetool/releases/tag/v1.5.24) - 2026-07-29

## [v1.5.23](https://github.com/krt-profit/basetool/releases/tag/v1.5.23) - 2026-07-29

### Fixed

- **CI: Der Build schlägt nicht mehr sporadisch mit einem abgeschnittenen OpenAPI-Dokument fehl.** Der Backend-Test, der `openapi.json` erzeugt, schrieb die 1,8-MB-Datei direkt an Ort und Stelle, während parallel laufende Frontend-Tests sie lesen — wer ins Schreibfenster geriet, scheiterte an unvollständigem JSON. Die Datei wird jetzt fertig geschrieben und erst dann an ihren Platz verschoben (REQ-API-007).

- **Discord-Registrierung: Nach der Freigabe geht es sofort weiter — ohne zweimaliges Ab- und Anmelden.** Die laufende Sitzung merkte sich das „noch nicht freigegeben" für ihre gesamte Laufzeit von 30 Tagen, sodass ein freigeschalteter Zugang weiter auf der Warteseite landete; die anschließend vergebenen Rollen und Einheiten waren aus demselben Grund erst nach einer weiteren Anmeldung sichtbar. Beides wird jetzt laufend aufgefrischt, und die Warteseite leitet nach der Freigabe von selbst ins Tool weiter (REQ-SEC-013, REQ-SEC-017, ADR-0122).

- **Entzogene Rollen und Rechte verschwinden jetzt auch aus einer laufenden Sitzung.** Der Abgleich mit dem Backend hat Rechte bisher nur hinzugefügt, nie entfernt — wer eine Rolle verlor, sah die zugehörigen Schaltflächen bis zum nächsten Anmelden weiter, bekam beim Klick aber eine Fehlermeldung. Entzogene Rollen und Rechte fallen jetzt innerhalb von ein bis wenigen Minuten aus der Oberfläche (REQ-SEC-013, ADR-0122).

- **Monitoring: Postgres-Fehlerzeilen lassen jetzt erkennen, welcher Client sie ausgelöst hat.** Mit dem Standardpräfix war eine abgewiesene Abfrage nicht zuordenbar — Anwendung, Exporter und eine manuelle `psql`-Sitzung sahen identisch aus, weil alle dieselbe Datenbankrolle verwenden. Beide Datenbanken schreiben jetzt Benutzer, Datenbank und `application_name` in jede Logzeile; Zeileninhalte werden weiterhin nicht geloggt (REQ-OBS-007).

## [v1.5.22](https://github.com/krt-profit/basetool/releases/tag/v1.5.22) - 2026-07-29

## [v1.5.21](https://github.com/krt-profit/basetool/releases/tag/v1.5.21) - 2026-07-29

### Fixed

- **Aufträge: Beim Bearbeiten eines Item-Auftrags gehen die bereits erfassten Herstellungen nicht mehr verloren.** Jedes Speichern löschte bisher alle Item-Zeilen und legte sie neu an, wodurch die Spalte „Hergestellt" jeder Zeile still auf 0 zurückfiel. Zeilen werden jetzt anhand ihrer ID an Ort und Stelle aktualisiert. Ist auf einer Zeile schon etwas hergestellt, lässt sie sich nicht mehr entfernen, das Item nicht mehr wechseln und die Anzahl nicht unter die hergestellte Menge senken; der Bauplan bleibt änderbar (REQ-ORDERS-032, ADR-0121).

- **Aufträge: Item-Zeilen mit veraltetem Bauplan werden jetzt erkannt und markiert.** Ändert der SC-Wiki-Abgleich, welches Item ein Bauplan herstellt, blieb die Auftragszeile am alten Bauplan hängen und zeigte still die Materialien eines fremden Rezepts an — betroffen waren zwei Zeilen (u. a. „Cryo-Star SL" mit dem HeatSink-Rezept). Solche Zeilen tragen in der Auftragsdetailansicht jetzt den Hinweis „Rezept veraltet"; ein stündlicher Prüflauf meldet sie zusätzlich ans Monitoring. Einmal Bearbeiten und Speichern leitet die Zeile korrekt neu ab (REQ-ORDERS-033, ADR-0121).

- **Raffinerie: Die Standortauswahl kann nicht mehr für einen ganzen Tag leer bleiben.** Seit der Umstellung auf die Terminal-basierte Ableitung wird die Liste aus Daten gebildet, die erst der UEX-Abgleich füllt. Schlug einer der Abgleichschritte davor fehl, wurden die Terminals gar nicht erst geholt — die Auswahl blieb leer und es ließ sich kein Raffinerieauftrag anlegen, bis der nächste Lauf 24 Stunden später griff. Die Terminals werden jetzt zuerst abgeglichen, und die Ableitung läuft auch dann, wenn ein späterer Schritt abbricht (REQ-REFINERY-020).

## [v1.5.20](https://github.com/krt-profit/basetool/releases/tag/v1.5.20) - 2026-07-28

### Fixed

- **Raffinerie: MIC-L5, ARC-L4 und Patch City stehen jetzt als Raffinerie zur Auswahl.** Die Auswahlliste richtete sich nach dem Stations-/Stadt-Kennzeichen von UEX, das diesen drei Standorten keine Raffinerie zuschreibt, obwohl UEX dort jeweils ein Raffinerie-Terminal führt. Maßgeblich ist jetzt das Terminal selbst. Umgekehrt entfallen die vier „People's Service Station"-Stationen (Alpha, Delta, Lambda, Theta), die als Raffinerie angeboten wurden, ohne eine zu besitzen. Die Liste wird beim nächsten UEX-Abgleich aktualisiert (REQ-REFINERY-020). Die Regel erkannte auch die Einträge, mit denen auditd beim Neustart seine eigenen Regeln neu lädt — das nächtliche `libc6`-Update startet auditd neu und löste damit einen Fehlalarm aus, obwohl keine SSH-Datei angefasst wurde. Sie wertet jetzt nur noch echte Dateizugriffe (`type=SYSCALL`) aus (REQ-OBS-010).

- **Monitoring: `FrontendLoginBroken` schlägt nicht mehr wegen Scanner-Verkehr in der Nacht an.** Besteht keine Keycloak-SSO-Sitzung, antwortet Keycloak auf die stille SSO-Erneuerung (`prompt=none`) mit `login_required` — der Normalfall bei jedem nicht angemeldeten Seitenaufruf, bisher aber als echter Anbieterfehler gezählt. Diese Antworten zählen jetzt als harmlos, sodass nur noch tatsächliche Fehler beim Token-Tausch alarmieren (REQ-OBS-011).

## [v1.5.19](https://github.com/krt-profit/basetool/releases/tag/v1.5.19) - 2026-07-26

### Fixed

- **Monitoring: Grafana läuft nicht mehr nach wenigen Stunden in einen Zustand, in dem es keine Prozesse mehr starten kann.** Der HTTPS-Healthcheck erzeugte pro Prüfung einen unaufgeräumten `ssl_client`-Zombieprozess; nach rund vier Stunden war das Prozesslimit des Containers erschöpft (zuletzt 493 Zombies), der Container galt zwei Tage als „unhealthy". Alle Monitoring-Container starten jetzt wie die App-Container mit einem aufräumenden Init-Prozess, sodass auch eine künftige Umstellung auf HTTPS den Fehler nicht erneut auslösen kann (REQ-OPS-019).

- **CI: Ein neuer Prüfschritt blockiert genau diesen Fehler künftig vor dem Merge.** Er prüft für jeden Container-Dienst, ob ein Healthcheck Prozesse abspaltet, ohne dass ein aufräumender Init-Prozess läuft — inklusive der Healthchecks, die aus den Images stammen. Ein Selbsttest stellt sicher, dass die Prüfung nicht unbemerkt wirkungslos wird (REQ-OPS-019).

- **Monitoring: `ContainerPidsHigh` überwacht jetzt alle Container gegen ihr jeweils eigenes Prozesslimit.** Die Regel prüfte nur die vier JVM-Container gegen einen fest verdrahteten Schwellwert, der für Container mit kleinerem Limit unerreichbar war — die Grafana-Erschöpfung blieb deshalb vollständig unbemerkt und ohne Alarmmail (REQ-OBS-014).

- **Monitoring: `SsePushChannelDead` alarmiert nicht mehr jede Nacht, in der niemand das Tool nutzt.** Die Bedingung „Nutzer sind online" stützte sich auf die Zahl der Sitzungen in Redis, die 30 Tage gültig bleiben und deshalb dauerhaft bei ~365 liegt — der Alert prüfte faktisch nur noch „keine SSE-Verbindung seit 30 Minuten" und meldete deshalb jede Nacht einen toten Benachrichtigungskanal. Er wertet jetzt echten Seitenverkehr aus (ohne Health-Probes, 404-Scanner und `/actuator*`) und schlägt nur noch an, wenn tatsächlich jemand die Anwendung benutzt (REQ-OBS-011).

## [v1.5.18](https://github.com/krt-profit/basetool/releases/tag/v1.5.18) - 2026-07-25

### Fixed

- **Release-Pipeline: Ein Aussetzer von Docker Hub bricht den Image-Bau nicht mehr ab.** Das BuildKit-Image wird vor dem Start des Builders mit Wiederholversuchen geladen, und die rein manifestbezogenen Jobs (Zusammenführen, Promotion) starten gar keinen Builder mehr — sie brauchten nie einen. Zuvor scheiterte der `keycloak-spi`-Job von v1.5.17 an einer Zeitüberschreitung beim anonymen Docker-Hub-Abruf, nachdem sein Build bereits erfolgreich war.

## [v1.5.17](https://github.com/krt-profit/basetool/releases/tag/v1.5.17) - 2026-07-25

### Added

- **Lager einbuchen: Bei genau einem zugeordneten Einsatz bzw. Auftrag muss die Menge nicht mehr eingetippt werden.** Bleibt das Mengenfeld der einzigen Zuordnung leer, wird die gesamte Menge des Eintrags dieser Zuordnung zugewiesen — pro Dimension unabhängig. Erst ab zwei Zuordnungen sind die Mengen wieder explizit anzugeben; eine eingetragene Menge hat immer Vorrang (REQ-INV-027).

- **Raffinerie: Im Einlager-Dialog lässt sich jede Ausgabezeile direkt als persönlicher Eintrag einlagern.** Bisher landete Raffinerie-Ausbeute immer im geteilten Staffelbestand und musste anschließend über „Mein Lager" umgebucht werden. Persönliche Zeilen tragen keine Zuordnung: Die Kombination mit einem Auftrag wird abgelehnt (der Auftrags-Selektor wird bei gesetztem Haken gesperrt), und die Mission des Raffinerieauftrags wird für diese Zeile nicht vermerkt (REQ-INV-035).

## [v1.5.16](https://github.com/krt-profit/basetool/releases/tag/v1.5.16) - 2026-07-25

### Changed

- **Bank-Anträge: Die letzte Spalte heißt jetzt „Entscheidung" statt „Aktionen", und der Über-Limit-Marker heißt „Über Limit" statt „Freigabe nötig".** Die Spalte zeigt nur bei offenen Anträgen Buttons, sonst die Person, die entschieden hat; der Marker wirkte auf bereits bestätigten Anträgen wie eine noch offene Aufgabe und ist dort jetzt zusätzlich grau statt gelb (REQ-BANK-041).

- **Monitoring: Der `JobOrderStale`-Alert schlägt jetzt erst nach 180 Tagen an (vorher 30).** Lang laufende Aufträge sind bei der aktuellen Org-Kadenz normal; `RefineryOrderStale`/`OperationStale` bleiben bei 30 Tagen (REQ-OBS-005).

- **Monitoring: `MailDroppedConfigDrift` alarmiert nicht mehr für den absichtlich abgeschalteten Mailversand.** Der Alert wertete den bewussten prod-Kill-Switch (`APP_MAIL_ENABLED=false`) als Konfigurationsfehler und mailte deshalb alle 4 Stunden; er greift jetzt nur noch bei aktiviertem, aber falsch konfiguriertem Versand (leerer SMTP-Host oder fehlende Sender-Bean) und bleibt bis zur Aktivierung des Mailversands still (REQ-OBS-011).

### Fixed

- **Stabilität: Das Frontend läuft nicht mehr am Rand seines Speicherlimits.** Der Container war mit 1024 MB tatsächlich zu klein — gemessen auf prod belegte er 935 MB (91 %) und selbst der nicht reduzierbare Anteil lag bei 87 %, sodass keine Heap-Einstellung das Limit hätte retten können. Das Limit steigt auf 1280 MB und der Heap ist auf 50 % (640 MB) begrenzt, weiterhin klar über den gemessenen 529 MB. Greift beim nächsten Deploy.

- **Stabilität: Backend und Ingest-Gateway können ihr Speicherlimit nicht mehr überschreiten.** Beide durften bis zu 75 % des Limits als Heap belegen, was zusammen mit dem übrigen Speicherbedarf 96 % (Backend) bzw. 90 % (Ingest) ergeben hätte — bisher unbemerkt, weil der Heap nie so weit wuchs. Die Obergrenzen liegen jetzt bei 57 % bzw. 60 % und damit immer noch beim 1,2- bis 1,7-Fachen des gemessenen Bedarfs; die Container-Limits bleiben unverändert. Greift beim nächsten Deploy.

- **Monitoring: Alloy-Speicherlimit auf 512 MB angehoben (`GOMEMLIMIT` 360 MiB).** Der Log-/Trace-Versender lief erneut auf über 90 % seines 384-MB-Limits und löste den `ContainerWorkingSetHigh`-Alarm aus, obwohl keine neue Last hinzukam; die Größen sind jetzt anhand des gemessenen konstanten Zusatzbedarfs (~47 MiB) als Budget statt als reiner Prozentwert bemessen. Greift beim nächsten Deploy.

- **Log-Hygiene: Anmeldungen mit noch nicht freigeschaltetem Konto fluten das Log nicht mehr.** Die erwarteten `PENDING_APPROVAL`-403 (die Shell eines wartenden Nutzers pollt mehrere Endpunkte pro Seitenaufruf) werden in Backend und Frontend jetzt auf DEBUG statt WARN geloggt; die Metrik/der `PendingApprovalBlockSpike`-Alert bleiben unverändert (REQ-OBS-001).

- **Log-Hygiene: Keycloak protokolliert Verbindungsabbrüche von Clients nicht mehr als ERROR.** `io.vertx.core.net.impl.ConnectionBase` (leere `<missing-log-message>`-ERROR-Zeilen bei abruptem TCP-Reset des Browsers/Scanners) ist per `KC_LOG_LEVEL` stummgeschaltet, damit diese wertlosen Zeilen keine ERROR-Spike-Alerts auslösen.

## [v1.5.15](https://github.com/krt-profit/basetool/releases/tag/v1.5.15) - 2026-07-23

### Changed

- **Filter bleiben jetzt app-weit pro Browser erhalten.** Alle Auswahl-Filter (Checkboxen, Mehrfachauswahlen, Dropdowns, Umschalter) werden im localStorage gespeichert und beim nächsten Öffnen wiederhergestellt — u. a. Materialbörse, Mein/Globales Lager, Raffinerie-Aufträge, Profitberechnung, Missionen/Operationen („Vergangene anzeigen"), Beförderung, Bank-Freigaben, Bank-Chart-Zeitraum sowie die Admin-Filterseiten. Text-Suchfelder und Datumsbereiche starten bewusst weiterhin frisch (REQ-UI-017, ADR-0120).

### Fixed

- **Preis-Übersicht: Die gesetzten Filter (Material, System, Loading Dock, Auto Load) bleiben jetzt erhalten.** Bisher setzte jeder Reload — oder das Öffnen am nächsten Tag — alle Filter zurück; die Auswahl wird nun pro Browser im localStorage gespeichert und beim Laden der Seite vor dem ersten Datenabruf wiederhergestellt (REQ-UI-016).

## [v1.5.14](https://github.com/krt-profit/basetool/releases/tag/v1.5.14) - 2026-07-23

### Fixed

- **Stabilität: Wiederkehrende Hibernate-Warnungen „Narrowing proxy to class Squadron/SpecialCommand" im Backend-Log beseitigt.** Mehrere Services luden eine Org-Einheit subklassen-typisiert nach, obwohl dieselbe Zeile in der Transaktion schon als Basis-Proxy vorlag (Missionsdetail, Eigentümer-Stempeln, Auftrags-Übergabe); sie laden jetzt polymorph über das OrgUnit-Repository — das entfernt die Warnung samt `==`-Identitätsbruch und spart zudem Abfragen (REQ-DATA-013, ADR-0119).

- **Log-Hygiene: Bot-Anfragen wie `GET /missions/common-handlers.js` erzeugen keine WARN-Zeilen und keinen 400er mehr.** Crawler, die Skript-Dateinamen relativ zur Seiten-URL auflösen, trafen die `/{id}`-Routen und scheiterten an der UUID-Konvertierung; solche asset-förmigen Pfade liefern jetzt ehrlich die 404-Seite und loggen nur noch auf DEBUG — echte fehlerhafte IDs behalten 400 + WARN (REQ-OBS-001).

## [v1.5.13](https://github.com/krt-profit/basetool/releases/tag/v1.5.13) - 2026-07-22

### Changed

- **Lager: Die Checkbox „Mit vorhandenem Bestand zusammenführen" sieht jetzt überall gleich aus.** Die Umbuchen-Dialoge (Mein Lager, Globales Lager) und das Einbuchen-Formular nutzen für die Zusammenführen- und Persönlich-Checkboxen jetzt dasselbe Layout wie die Profilseite: Checkbox links, Beschriftung mit Hilfetext rechts gestapelt (REQ-INV-026).

### Fixed

- **Hangar: Die „Fitted (Einsatzbereit)"-Checkbox im Schiff-Dialog wird wieder als sauberes KRT-Quadrat dargestellt.** Die seitenweite Formularfeld-CSS-Regel zog Innenabstand und Rahmen der Checkbox mit; der frühere Teil-Fix stellte nur die Breite wieder her. Checkboxen und Radios sind jetzt auf allen Seiten mit dieser seitenweiten Formularfeld-Regel ausgenommen — auch vorsorglich dort, wo heute noch keine Checkbox in einer Formulargruppe steht (REQ-UI-001).

- **Admin-Bereich: Checkboxen in „Missionsdaten" und „Materialien verwalten" werden wieder als kompakte KRT-Kästchen dargestellt.** Eine seitenweite Formularfeld-Regel streckte die Checkboxen (Führungsposition/Einsatzleiter im Job-Dialog, die Flag-Schalter im Material-Anlegen-Dialog) auf volle Breite mit Innenabstand; die Regel klammert Checkboxen und Radios jetzt aus.

- **Lager/Aufträge: Material-, Ort-, Item- und Konto-Auswahlfelder zeigen jetzt einen passenden Suchhinweis.** Bisher stand in vielen dieser Comboboxen (z. B. Material und Ort beim Einbuchen) irrtümlich der Nutzer-Platzhalter „Nutzer suchen oder wählen…"; jetzt nennt jedes Feld, was es durchsucht — auch die „Keine Treffer"-Meldung ist je Feldart korrekt (REQ-FE-011/016).

- **Lager einbuchen / Auftrag erstellen: Die Material/Item-Auswahlknöpfe (Radio-Buttons) sind wieder normal groß.** Ein seitenweiter Eingabefeld-Stil hatte die Radio-Buttons zu übergroßen Ovalen aufgeblasen; sie erscheinen jetzt als reguläre KRT-Radio-Kreise neben ihrer Beschriftung (REQ-FE-016).

- **Lager einbuchen: Die Optionen „Persönlicher Eintrag" und „Mit vorhandenem Bestand zusammenführen" haben jetzt dasselbe Format.** Beide Zeilen zeigen die Checkbox links und daneben Beschriftung mit Hilfetext untereinander; zuvor lief der lange Text der Zusammenführen-Option unschön um die Checkbox herum (REQ-INV-026).

## [v1.5.12](https://github.com/krt-profit/basetool/releases/tag/v1.5.12) - 2026-07-22

### Added

- **Monitoring: Zwei neue Log-Alerts schließen die Lücke, durch die der 41-Minuten-Vorfall am 22.07.2026 ohne einen einzigen Alarm blieb.** `FrontendKeycloakBackchannelFailing` schlägt an, wenn Token-Aufrufe an Keycloak anhaltend mit Verbindungsabbrüchen scheitern; `HealthContributorHanging` meldet jeden Health-Contributor, der länger als 10 s braucht (Backend und Frontend) — beides Zustände, in denen Login/Session-Erneuerung bzw. der Container-Healthcheck bereits leiden, während alle bisherigen Alarme grün blieben (REQ-OBS-014).

### Fixed

- **Login/Session: Hängende Token-Aufrufe an Keycloak brechen jetzt nach Sekunden sauber ab statt minutenlang zu blockieren.** Beim Vorfall am 22.07.2026 blieben Token-Erneuerungen 41 Minuten lang mitten im Senden stecken (transiente Netzstörung auf dem Proxy-Pfad) — clientseitig griff kein Timeout, erst der Proxy kappte jede Verbindung nach ~60 s, und jeder Abbruch war eine verlorene Erneuerung. Der Token-Client hat jetzt Lese-/Schreib-Timeouts (3 s) wie der übrige Backend-Transport; ein hängender Aufruf mündet schnell in die stille Neuanmeldung (REQ-SEC-012, ADR-0117).

- **Stabilität: Die Redis-Bereitschaftsprüfung kann nicht mehr minutenlang hängen.** Trotz des 2-Sekunden-Timeouts aus v1.5.10 stauten sich beim Vorfall am 22.07.2026 Health-Checks bis zu 14 Minuten hinter einer blockierten Lettuce-Verbindungsanforderung, die kein Kommando-Timeout erreicht. Die Redis-Prüfung ist jetzt zusätzlich auf Health-Ebene hart auf 3 s begrenzt und meldet danach ehrlich „nicht bereit" (ADR-0118).

## [v1.5.11](https://github.com/krt-profit/basetool/releases/tag/v1.5.11) - 2026-07-21

### Fixed

- **Materialbörse: Beim Öffnen wurden kurzzeitig beide Boards (Angebote und Gesuche) übereinander angezeigt.** Auf der Standardansicht (Angebote) rendert der Server jetzt wieder nur das Angebots-Board; zuvor wurde das Gesuche-Board wegen einer Thymeleaf-Vorrangfalle (`th:replace` schlägt `th:if` auf demselben Element) bedingungslos zusätzlich eingefügt, bis ein Tab-Wechsel es wegräumte (REQ-MARKET-015).

- **Materialbörse-Gesuche: Kosmetik im „Material/Item suchen"-Dialog.** Das Beschreibungsfeld nutzt jetzt die volle Dialogbreite (statt der schmalen Standardbreite eines Textfelds), und die leere „Gesucht —"-Zeile unter der Material-/Item-Auswahl erscheint nicht mehr beim Erstellen — diese Zeile ist nur beim Bearbeiten eines Gesuchs sinnvoll und war nur wegen eines CSS-Vorrangproblems im Erstellen-Dialog sichtbar (REQ-MARKET-015).

## [v1.5.10](https://github.com/krt-profit/basetool/releases/tag/v1.5.10) - 2026-07-21

### Added

- **Materialbörse: Mitglieder können jetzt Gesuche einstellen, nicht nur Angebote.** Neben „Alle Angebote"/„Meine Angebote" gibt es jetzt die Tabs „Alle Gesuche"/„Meine Gesuche"; die Buttons wechseln je nach Ansicht zwischen „Material anbieten"/„Item anbieten" und „Material suchen"/„Item suchen". Ein Gesuch nennt ein Material oder craftbares Item, eine Markdown-Beschreibung, optional eine Mindestqualität und die gewünschte Menge (SCU oder Stück); andere Mitglieder signalisieren „Ich kann liefern" und der Suchende wird benachrichtigt — Standort und Lieferantennamen bleiben wie bei Angeboten privat bzw. nur für den Suchenden sichtbar (REQ-MARKET-015…020, ADR-0116).

- **Aufträge: Neue Itemsammelübersicht zum Einsammeln der hergestellten Items.** Analog zur Materialsammelübersicht gibt es für Item-Aufträge jetzt eine eigene Sammelseite (erreichbar über die Item-Übergaben-Werkzeugleiste), auf der die dem Auftrag zugeordneten Items eingesammelt werden: Besitzer und Standort lassen sich umbuchen (die Auftragszuordnung bleibt dabei erhalten) und jede Einheit als geliefert markieren. Der bisher irrtümlich auf die Materialsammelübersicht zeigende Link bei Item-Aufträgen führt jetzt hierher (REQ-ORDERS-031).

- **Auftragsdetails: Die aggregierte Materialliste zeigt jetzt eine Spalte „Vorhanden".** Zwischen „Gesamtmenge" und „Eingetragen" steht die dem Auftrag aus dem Lager zugeordnete Menge je Material. So ist auf einen Blick erkennbar, wie viel schon beschafft ist — die Lücke zur Gesamtmenge ist der noch zu beschaffende Rest, klar getrennt von der Spalte „Offen" (noch ausstehende Staffel-Eintragungen) (REQ-ORDERS-026).

### Changed

- **Auftragsdetails: Der einem Item zugeordnete Lagerbestand wird jetzt direkt beim Aufklappen der Item-Zeile angezeigt.** Im Tab „Bestellte Items" zeigt jede aufgeklappte Item-Zeile unter dem Bedarf je Stück, bei wem und wo die dem Auftrag zugeordneten Einheiten liegen — analog zur Materialliste. Der bisherige separate „Item-Bestand"-Block darunter entfällt; das Einsammeln (Umbuchen und Als-geliefert-Markieren) passiert auf der neuen Itemsammelübersicht (REQ-ORDERS-028).

- **Auftragsdetails: Die Kennzahl „Offene Menge" trennt jetzt SCU- und Stück-Material.** Bisher wurden SCU- und Stück-Mengen zu einer einzigen, fälschlich als „SCU" beschrifteten Zahl addiert. Die Kachel zeigt die offene Menge jetzt je Einheit als eigene Zahl (SCU bzw. Stück); ein Auftrag mit nur einer Einheitenart zeigt weiterhin genau eine Zahl (REQ-ORDERS-026).

- **Edge-Rate-Limiting: Verbindungslimit wieder eng pro Client (500/IP).** Nachdem das Frontend-Netz per ADR-0112 auf natives IPv6 umgestellt wurde und die echte Client-IP (v4 und v6) am Reverse-Proxy ankommt, wurde das gleichzeitige Verbindungslimit von der Sofortmaßnahme 10000 auf 500 pro Client gesenkt — großzügig für echte Nutzer (mehrere Tabs mit SSE/WebSocket), aber wieder eine wirksame Obergrenze gegen Fluten (REQ-SEC-023, ADR-0112).

- **Edge-Rate-Limiting: IPv6-Clients werden pro `/64`-Netz gezählt statt pro Einzeladresse.** Das Verbindungs- und Anfragelimit am Reverse-Proxy fasst wechselnde IPv6-Adressen desselben Anschlusses (Privacy-Extension-Rotation im unteren 64-Bit-Teil) jetzt in einem gemeinsamen Zähler zusammen; IPv4 bleibt pro voller Adresse. Zudem in der Produktion verifiziert und klargestellt: keycloak/ingest/grafana brauchen kein eigenes IPv6-Netz — alle Vhosts teilen sich den einen `:443`-Eingang über `net-proxy-frontend`, sodass auch dort die echte Client-IP greift; die Bridge-Gateway-Adressen in deren Logs sind interner Hairpin-Verkehr (Blackbox-Proben und OIDC-Aufrufe), keine maskierten Nutzer (REQ-SEC-023, ADR-0112).

### Fixed

- **Raffinerie-Screenshot-Import: Die Raffiniermethode wird jetzt auch bei einer leicht verlesenen Schreibweise erkannt.** Der Import ordnete die Methode nur bei exakter Übereinstimmung zu, sodass die vom lokalen Bilderkenner „autokorrigierte" Schreibweise (`DINYX SOLVATION` statt des Spielbegriffs *Dinyx Solventation*) als „nicht zugeordnet" liegen blieb. Die Zuordnung nutzt jetzt — wie schon bei Materialien — eine mehrstufige Kette (exakt → kanonisch → unscharfer Nächster-Treffer) über die geschlossene Menge der neun Raffiniermethoden; unpassender Text bleibt weiterhin unzugeordnet (REQ-REFINERY-008).

- **Benachrichtigungen: Der Live-Push (SSE) funktioniert wieder.** Der Echtzeit-Kanal für die Glocke lieferte serverseitig keine Antwort-Header mehr aus (jeder Stream lief in einen Proxy-Timeout), weil das Frontend-Relay seine Antwort erst beim ersten weitergeleiteten Backend-Event committete — und dieser Schreibzugriff kommt auf Spring Boot 4 / Tomcat 11 aus einem Nicht-Container-Thread, der die Antwort nicht abschließt. Das Relay committet jetzt sofort auf dem Request-Thread (ein unsichtbares initiales SSE-Kommentar); der 60-Sekunden-Poll war durchgehend der Fallback, sodass nur die Live-Aktualisierung betroffen war (REQ-NOTIF-010, ADR-0113).

- **Stabilität: Ein langsames oder blockiertes Redis kann das Frontend nicht mehr in die Wartungsseite kippen.** Die Bereitschaftsprüfung (Readiness) bezieht den Redis-Zustand ein; dessen reaktiver PING lief ohne gesetztes Kommando-Timeout in Lettuces 60-Sekunden-Standard und konnte den Health-Endpunkt minutenlang blockieren, sodass der Docker-Healthcheck (5-Sekunden-Fenster) scheiterte und der Container als „unhealthy" in die Wartungsseite kippte. Das Redis-Kommando- und Verbindungs-Timeout ist jetzt auf 2 s begrenzt (per Umgebungsvariable änderbar) — ein hängendes Redis wird schnell und ehrlich als „nicht bereit" gemeldet statt zu blockieren, ein echter Redis-Ausfall lässt die Readiness weiterhin korrekt fehlschlagen (REQ-OPS-003, ADR-0114).

- **Lager-Einbuchen: Die Auftrags-Auswahl beim Zuordnen zeigt jetzt zuverlässig die passenden Aufträge.** Im Einbuchen-Formular filterte die „Aufträge zuordnen"-Auswahl nach den Materialzeilen des Auftrags — die bei Fertigungsaufträgen (Item-Aufträge) leer sind. Dadurch fehlten je nach gewähltem Material mal alle, mal nur einige Aufträge, oder es erschienen vor der Materialauswahl alle. Der Filter nutzt jetzt die auftragsartunabhängige Menge der benötigten Materialien (`requiredMaterialIds`), sodass auch Fertigungsaufträge auftauchen, deren Bauplan das Material verbraucht (REQ-ORDERS-018).

- **Login/Session: Gelegentliche „Fehler beim Laden" bzw. erzwungene Neuanmeldungen durch abgerissene Keycloak-Verbindungen behoben.** Die Token-Aufrufe des Frontends an Keycloak (Login und automatische Token-Erneuerung) laufen über den öffentlichen Reverse-Proxy und nutzten Spring Securitys Standard-Verbindungspool, der veraltete Keepalive-Verbindungen nicht aussortiert; griff eine Token-Erneuerung auf eine bereits vom Proxy geschlossene Verbindung zu, brach sie mit einem Verbindungsfehler ab. Beide Token-Wege bekommen jetzt einen eigenen Verbindungspool, der ungenutzte Verbindungen vor dem Keepalive-Timeout des Proxys wegräumt; reine Transportänderung ohne Auswirkung auf die Token-Erneuerungslogik (REQ-SEC-012, ADR-0115).

## [v1.5.9](https://github.com/krt-profit/basetool/releases/tag/v1.5.9) - 2026-07-20

### Fixed

- **Missionen: Ein einmal eingetragener Verantwortlicher einer Einheit lässt sich im „Einheit bearbeiten"-Dialog wieder entfernen.** Bisher bot das Auswahlfeld keinen Weg zurück auf „automatisch: Schiffseigner": der Eintrag „— automatisch —" war nicht auswählbar und ein geleertes Feld sprang beim Verlassen auf den alten Namen zurück. Zurücksetzen geht jetzt auf zwei Wegen — über die anwählbare „— automatisch —"-Zeile im Dropdown oder durch schlichtes Löschen des Textes im Feld (das geleerte Feld bleibt leer). Gilt für alle optionalen Benutzer-Auswahlfelder (REQ-FE-011, ADR-0053).

- **Mitgliederverwaltung: Der Button „Zweite Staffel hinzufügen" öffnet jetzt wieder den Eingabebereich für die zweite Staffel.** Bisher verschwand beim Klick nur der Button, ohne dass der zweite Staffel-Slot erschien: Dessen Sichtbarkeit wird über die CSS-Klasse `krtm-hidden` gesteuert, das Skript versuchte ihn aber über einen Inline-`display`-Stil einzublenden, der die Klasse nicht übersteuert. Der Slot wird jetzt ebenfalls per Klasse ein- und ausgeblendet (REQ-ORG-017, ADR-0093).

- **Edge-Rate-Limiting: „Too many requests“ und Wartungsseite bei normaler Nutzung behoben.** Das Verbindungslimit am Reverse-Proxy (NPM) wirkte für IPv6-Clients faktisch global statt pro IP — weil `:443` auch auf IPv6 veröffentlicht ist, das Container-Netz aber nur IPv4 kann, leitet Docker jeden IPv6-Client über die Bridge-Gateway-Adresse weiter, und da Dual-Stack-Browser IPv6 bevorzugen, landete fast aller echte Verkehr in einem gemeinsamen Zähler. Schon wenige gleichzeitige Nutzer auf der Missionsseite überschritten so die 60er-Grenze, wodurch legitime Anfragen mit 429 abgewiesen wurden und Reconnect-Stürme das Frontend in die Wartungsseite trieben. Das Verbindungslimit wurde als Sofortmaßnahme auf 10000 angehoben; der eigentliche Fix (echte Client-IP wiederherstellen) ist als ADR-0112 geplant (REQ-SEC-023).

- **Edge: Natives IPv6 auf dem Proxy-Netz stellt die echte Client-IP wieder her (ADR-0112).** IPv6-Clients kommen jetzt per Kernel-DNAT mit ihrer echten Adresse an — statt über die Bridge-Gateway-IP des userland-Relays — die Voraussetzung, um das Verbindungslimit am Edge wieder eng pro Client (~500/IP) statt global zu setzen. Das Ausrollen erfordert ein kurzes Wartungsfenster (Netz-Recreate); der Live-Wert bleibt vorerst 10000 (REQ-SEC-023).

## [v1.5.8](https://github.com/krt-profit/basetool/releases/tag/v1.5.8) - 2026-07-20

### Fixed

- **Discord-Registrierungen: Ein bereits stecken gebliebener „Verknüpfen"-Antrag lässt sich jetzt durch erneutes Verknüpfen abschließen, statt mit „Ein unerwarteter Fehler ist aufgetreten" abzubrechen.** War der Wegwerf-Discord-Account durch einen früheren Fehlversuch schon aus Keycloak gelöscht, lieferte die Abfrage seiner Discord-Verknüpfung einen `404`, der ungefangen als Server-Fehler durchschlug und den in v1.5.7 ergänzten Rückgriff auf den lokal gespeicherten Discord-Bezug nie erreichte. Ein nicht mehr existierender Keycloak-Benutzer wird bei dieser Abfrage nun als „keine Verknüpfung" behandelt, sodass der lokale Rückgriff greift und der Antrag sauber verknüpft wird (REQ-SEC-026, ADR-0111).

## [v1.5.7](https://github.com/krt-profit/basetool/releases/tag/v1.5.7) - 2026-07-20

### Fixed

- **Discord-Registrierungen: „Verknüpfen" schlägt nicht mehr mit einer Datenbankfehlermeldung fehl.** Das Verknüpfen eines Freigabeantrags mit einem bestehenden Account (v1.5.6) brach beim Schreiben des Audit-Eintrags immer mit einer Constraint-Verletzung ab, weil der neue Vorgangstyp `LINKED` in der Prüfbedingung der Freigabe-Historie fehlte (Migration V223 ergänzt ihn). Zusätzlich wird der Wegwerf-Discord-Account in Keycloak jetzt erst **nach** dem erfolgreichen Datenbank-Abgleich gelöscht und der Discord-Bezug notfalls aus dem lokalen Datensatz gelesen, sodass ein zuvor stecken gebliebener Antrag durch erneutes „Verknüpfen" sauber abgeschlossen werden kann (REQ-SEC-026, ADR-0111).

## [v1.5.6](https://github.com/krt-profit/basetool/releases/tag/v1.5.6) - 2026-07-20

### Added

- **Discord-Registrierungen: „Verknüpfen" verbindet einen Freigabeantrag mit einem bestehenden Account.** Meldet sich ein Mitglied, das bereits einen Account hat, über Discord an und taucht als neuer Freigabeantrag auf (etwa weil sein Discord-Name vom Basetool-Namen abweicht), kann ein Admin den Antrag jetzt in der Freigabe-Liste per Account-Suche mit dem bestehenden Account verknüpfen, statt einen zweiten Account anzulegen — die Discord-Anmeldung wandert auf den Bestandsaccount und der doppelte Antrag wird entfernt (REQ-SEC-026). Setzt das Keycloak-Recht `manage-users` für das Sync-Service-Konto voraus.

## [v1.5.5](https://github.com/krt-profit/basetool/releases/tag/v1.5.5) - 2026-07-20

### Changed

- **Datenschutz: Die Datenschutzerklärung wurde auf den aktuellen Stand des Basetools gebracht.** Ergänzt bzw. präzisiert wurden u.a. die Materialbörse (organisationsweite Sichtbarkeit von Angeboten), das persönliche Inventar, Beförderungs-/Bewertungsdaten, Angaben zu nicht registrierten Personen, die technische Ablaufverfolgung (Tracing, 14 Tage) und die Aufbewahrungsfristen (Anwendungsprotokolle 31 Tage, Metriken 180 Tage, Benachrichtigungen 90 Tage) sowie die korrekte Lösch- und Aufbewahrungslogik für Bankbuchungen. Der veraltete Cookie-Hinweis zu `orders_filter_status` entfällt.

- **Aufträge: Der Statusfilter der Auftragsübersicht wird jetzt wie der Staffelfilter im Browser (localStorage) statt in einem Cookie gespeichert.** Das 30-Tage-Cookie `orders_filter_status` entfällt ersatzlos; die zuletzt gewählten Status werden clientseitig gemerkt und serverseitig angewandt (REQ-ORDERS-027).

### Fixed

- **Discord-Registrierungen: Der Server-Nickname wird jetzt auch angezeigt, wenn ein Mitglied keinen eigenen Server-Nickname gesetzt hat.** Bisher blieb die Spalte leer, sobald ein Mitglied im Server unter seinem globalen Anzeigenamen auftritt (ohne separaten Server-Nickname); erfasst wird nun der im Server angezeigte Name (Server-Nickname, sonst globaler Anzeigename), damit ein Admin den Antrag einer Person zuordnen kann (REQ-DATA-008).

- **Sicherheit: Eingebetteter Tomcat auf 11.0.24 angehoben (CVE-2026-59083, CVE-2026-59084).** Der von Spring Boot vorgegebene Tomcat 11.0.23 war über die RewriteValve (Dekodierung von „+" zu einem Leerzeichen beim Umschreiben, wodurch in manchen Konfigurationen eine URL-basierte Sicherheitsregel umgangen werden konnte) sowie über die EncryptInterceptor-Härtung angreifbar; die von der NVD kritisch bewertete Version wird jetzt auf die gepatchte 11.0.24 erzwungen. Der OWASP-Abhängigkeits-Scan ist damit wieder grün — die parallel gemeldeten Vert.x-4.5.x-Funde (CVE-2026-15075/-15076) waren Fehlalarme, da diese Bibliotheken nur auf dem Compile-Klassenpfad des Keycloak-SPI-Moduls liegen, nie ausgeliefert werden und zur Laufzeit vom Keycloak-Container bereitgestellt werden; sie wurden begründet unterdrückt.

## [v1.5.4](https://github.com/krt-profit/basetool/releases/tag/v1.5.4) - 2026-07-19

### Fixed

- **Extractor: Der „An Basetool senden"-Import-Link zeigt nicht mehr „Import-Link abgelaufen oder ungültig", wenn der Browser die Seite doppelt lädt.** Bei manchen Nutzern (beobachtet mit Firefox) öffnete der Browser die vorbefüllte Seite zweimal kurz hintereinander; der erste Aufruf verbrauchte den einmaligen Import-Link, der zweite zeigte den Ablauf-Hinweis — jeder Sendevorgang schlug fehl, während der manuelle JSON-Import weiter funktionierte. Der Seitenaufruf verbraucht den Link jetzt nicht mehr; das geschieht erst über eine gezielte Aktion der Seite, die ein vorausschauendes Vorladen des Browsers nicht auslöst. Betrifft Raffinerie- und Blueprint-Import gleichermaßen (REQ-INGEST-004, ADR-0110).

- **Monitoring: Der Alarm `LokiWriteFailing` feuert nicht mehr dauerhaft, wenn ein nahezu stiller Container (z. B. `redis-exporter`) seine letzte Logzeile länger als Lokis Annahmefenster von 168 h unverändert stehen lässt.** Alloys Docker-Tailer liefert diese letzte Zeile bei jeder Wiederverbindung erneut; sobald sie 168 h überschritt, wies Loki jede Wiederholung mit „timestamp too old" (HTTP 400) ab, wodurch der Alarm ununterbrochen auslöste (die beiden `postgres`-Exporter und `alertmanager` wären wenige Tage später gefolgt). Ein `stage.drop older_than = "167h"` in der Container-Log-Pipeline verwirft solche veralteten Wiederholungen jetzt generisch an der Quelle, bevor sie Loki erreichen (REQ-OBS-007).

## [v1.5.3](https://github.com/krt-profit/basetool/releases/tag/v1.5.3) - 2026-07-18

### Changed

- **Kartellbank: Die mittlere Freigabestufe des KRT-Kontos genehmigt jetzt die Bankleitung statt des Bereichsleiters Profit.** Auszahlungen/Transfers zwischen den beiden Schwellen `T1` und `T2` gehen zur Freigabe an die Bankleitung, darüber weiterhin an die Organisationsleitung; die Anzeige in „KRT-Freigaben" und auf der Kontodetailseite ist entsprechend angepasst (REQ-BANK-047, ADR-0109).

- **Kartellbank: Bucht ein Bankmitarbeiter direkt über seiner Freigabegrenze aus dem KRT-Konto, wird die Buchung jetzt automatisch als Freigabeantrag angelegt statt abgewiesen.** Der Betrag wird nicht gebucht, sondern als band-gerouteter Antrag (Bankleitung bzw. Organisationsleitung) eingereicht; der Mitarbeiter erhält den Hinweis, dass der Antrag erst genehmigt werden muss und er der Bankleitung Bescheid geben soll (REQ-BANK-047, ADR-0109).

### Fixed

- **Kartellbank: Fachliche Buchungskonflikte zeigen wieder eine verständliche Meldung statt „Ein unerwarteter Fehler ist aufgetreten. Unser Team wurde informiert."** Mehrere Bank-Konfliktcodes (u. a. Begründung erforderlich, Freigabe erforderlich, Gebühr übersteigt Betrag, Antrag nicht mehr offen) waren im Frontend auf keine Meldung abgebildet und fielen auf den generischen Text zurück; sie erscheinen jetzt mit ihrer konkreten Ursache im Buchungsdialog (REQ-BANK-047).

## [v1.5.2](https://github.com/krt-profit/basetool/releases/tag/v1.5.2) - 2026-07-18

### Added

- **Lager/Materialbörse: Items lassen sich jetzt direkt in „Mein Lager" (Ansicht „Items") für die Börse freigeben.** Jede Item-Bestandszeile trägt – wie schon die Material-Zeilen – die Checkbox „Für Börse freigeben"; das Anhaken öffnet den Freigabe-Dialog und stellt den Posten als bestandsgedecktes Item-Angebot ein, das Abhaken nimmt ihn wieder heraus. Bisher war das nur über „Material anbieten" auf der Börse selbst möglich (REQ-MARKET-002/014).

- **Mein Lager: Ein Button „Alle markieren" vor „Markierte ausbuchen" wählt alle Einträge der aktuellen Ansicht aus.** In „Mein Lager" (`/inventory/my`, Material- und Items-Ansicht) markiert der Button jeden Eintrag der aktuellen Filteransicht — auch in eingeklappten Stapeln und über die Seitenblätterung hinweg —, sodass man vor „Markierte ausbuchen" nicht mehr jeden Eintrag einzeln anhaken muss; ein erneuter Klick hebt die Auswahl wieder auf (REQ-INV-034).

### Changed

- **Materialbörse: Der Dialog „Material anbieten" hat jetzt eine Material/Item-Auswahl (Radiobuttons) über dem Auswahlfeld.** Standard ist „Material"; „Item" zeigt nur die eigenen bestandsgedeckten Item-Lagerposten, „Material" nur Materialposten (vorher mischte die Liste beide). Gefiltert wird server-seitig, damit auch bei vielen Posten jeder Eintrag der gewählten Art auffindbar bleibt; ein Wechsel der Art setzt eine bereits getroffene Auswahl zurück (REQ-MARKET-002).

### Fixed

- **Extractor: Der „An Basetool senden"-Import-Link läuft nicht mehr vorzeitig ab.** Die Gültigkeit des einmaligen Handoffs wurde von 5 auf 30 Minuten angehoben (env-übersteuerbar via `APP_INGEST_HANDOFF_TTL`), weil das Öffnen der vorbefüllten Seite ein separater manueller Klick nach dem Senden ist — langsamere Nutzer sahen sonst durchgängig „Import-Link abgelaufen oder ungültig", während der manuelle JSON-Import funktionierte. Gateway und Frontend protokollieren den Handoff jetzt zusätzlich mit einem nicht umkehrbaren Subject-/ID-Hash (nie das Rohsubjekt oder die ID), damit ein künftiger Fehlschlag eindeutig als Ablauf oder Subject-Abweichung erkennbar ist (REQ-INGEST-003).

- **Materialbörse: Im Dialog „Material anbieten" klappt die Material-/Item-Auswahlliste nicht mehr sofort beim Öffnen auf und verdeckt so die übrigen Eingabefelder.** Das Dropdown bleibt geschlossen und öffnet sich erst, wenn man in das Auswahlfeld klickt oder tippt; es schließt wieder bei Auswahl, Klick außerhalb oder Escape. Gilt ebenso für die Item-Auswahl im Dialog „Item anbieten" (REQ-MARKET-002).

## [v1.5.1](https://github.com/krt-profit/basetool/releases/tag/v1.5.1) - 2026-07-17

### Fixed

- **Monitoring: Der Alarm `SyncZeroItems` (UEX-/SC-Wiki-Katalogsync) feuert nicht mehr fälschlich, wenn das Backend häufiger als der Tagesrhythmus des Syncs neu startet.** Der Item-Zähler wird lazy registriert und bei jedem Neustart zurückgesetzt, sodass `increase[48h]` trotz kerngesundem Sync (z. B. ~7499 importierte Zeilen pro Lauf) 0 las und der Alarm dauerhaft feuerte; die Regel prüft jetzt einen tatsächlich beobachteten erfolgreichen Lauf (`executions_total{outcome="success"}`) im Fenster statt „letzter Erfolg < 48h" — ein echter Leer-200-Ausfall löst weiterhin aus (REQ-OBS-014).

- **Deploy: Änderungen an der Compose-Definition der Monitoring-Container (mem_limit/Env/Mounts in `docker-compose.monitoring.yml`) werden jetzt auf die laufenden Container angewandt.** Bisher wurde nur Drift in den Config-Unterverzeichnissen reconcilt, weshalb z. B. Alloy tagelang mit dem alten 256M-Limit lief, obwohl auf Platte 384M standen; `reconcile_monitoring_reloads` führt nun zusätzlich ein pro-Service idempotentes `docker compose … up -d` aus (REQ-OPS-013).

- **Monitoring: cAdvisor überlebt jetzt einen containerd-Neustart, ohne Container-Serien zu verlieren (behebt wiederkehrende `CoreContainerMetricsMissing`-Alarme).** Der containerd-Socket wird als Verzeichnis statt als Einzeldatei gemountet, damit ein neuer Socket-Inode sichtbar bleibt und cAdvisor nicht auf den toten Inode festgenagelt wird (REQ-OBS-014, ADR-0072).

- **Logging: Der langlebige Benachrichtigungs-Stream wird nicht mehr fälschlich als „Slow request" gemeldet.** Der SSE-Relay-Endpunkt (`/api/v1/notifications/stream` bzw. `/notifications/stream`) hält die Verbindung bauartbedingt bis zu 30 Minuten offen; bisher überschritt er dadurch bei jedem Verbindungsende die Slow-Request-Schwelle und flutete das Zugriffslog mit falschen WARN-Zeilen. Er wird jetzt wie schon bei den Latenzmetriken (REQ-OBS-009) von der WARN-Eskalation ausgenommen und behält seine eine INFO-Zeile (REQ-OBS-001).

- **Logging: Die Hibernate-Validator-Deprecation-Warnungen zu `@Valid` auf Sammlungen entfallen.** Mehrere DTOs trugen `@Valid` am Listen-Container statt am Element-Typ, was beim Start je Feld ein `HV000271: Using @Valid on a container … is deprecated` auslöste; die Annotation steht jetzt am Element-Typ (`List<@Valid X>`), die Validierung bleibt unverändert.

## [v1.5.0](https://github.com/krt-profit/basetool/releases/tag/v1.5.0) - 2026-07-17

### Added

- **Lager: Items (Gegenstände mit Blueprint) sind jetzt als eigener Bestand im Lager erfassbar (Backend/API).** Lagereinträge tragen entweder ein Material (mit Qualität) oder ein Item (ohne Qualität, ganze Stückzahlen); Item-Einträge lassen sich nur Item-Aufträgen zuordnen, die das Item anfordern, und nie Missionen (REQ-INV-029…031, Migration V220, ADR-0101). Die Lager-Ansichten im Frontend folgen in einer separaten PR.

- **Lager: Die Lager-Seiten haben jetzt einen Material ↔ Items-Umschalter.** Übersicht, „Mein Lager" und „Globales Lager" zeigen wahlweise den Material- oder den neuen Item-Bestand (Item-Baum ohne Qualitäts-/Einsatzspalten, ganze Stückzahlen, Filter nur über tatsächlich eingelagerte Items und Aufträge); dazu kommt eine Item-Detailseite (`/inventory/game-item/{id}`), und Änderungen anderer Nutzer erscheinen in beiden Ansichten live (REQ-INV-030).

- **Aufträge: Beim Erfassen einer Herstellung werden die produzierten Items direkt ins Lager eingebucht.** Der Herstellen-Dialog hat dafür einen neuen Abschnitt „Einlagerung": Lagerort (Pflicht), Eigentümer (vorbelegt mit dem Buchenden, inkl. Org-Einheiten-Auswahl) und die Optionen „persönlich" bzw. „dem Auftrag zuordnen" (Standard: zugeordnet); das Einlagern wird als eigenes Audit-Ereignis („Aus Herstellung eingelagert") protokolliert und erscheint bei anderen Nutzern live im Lager (REQ-INV-032, REQ-ORDERS-025).

- **Aufträge: Die Auftragsdetailseite zeigt bei Item-Aufträgen jetzt ein „Item-Bestand"-Panel mit dem dem Auftrag zugeordneten Item-Bestand.** Das Panel auf dem Tab „Bestellte Items" gruppiert die zugeordneten Lagereinträge je Item (Besitzer, Ort, zugeordnete Stückzahl, Hergestellt-/Bestellt-Kontext) und bietet je Eintrag einen Geliefert-Schalter wie die Materialsammelübersicht; Änderungen (Herstellung, Lager-Zuordnung, Geliefert-Wechsel) erscheinen ohne Neuladen und auch bei anderen Betrachtern live (REQ-ORDERS-028).

- **Lager: Das Einbuchen-Formular hat jetzt einen Material ↔ Item-Umschalter.** Im Item-Modus wird das Item über eine durchsuchbare Katalogsuche gewählt (nur Items mit Blueprint), die Menge in ganzen Stück erfasst (ohne Qualität und ohne Zusammenführen-Checkbox — Items werden automatisch zusammengeführt) und die Auftragszuordnung auf Item-Aufträge gefiltert, die das Item anfordern; Einsatz-Zuordnungen entfallen im Item-Modus (REQ-INV-029/031).

- **Materialbörse: Items lassen sich jetzt direkt aus dem Item-Lagerbestand anbieten (bestandsgedecktes Item-Angebot).** Über „Material anbieten" erscheinen neben den Material- auch die eigenen Item-Lagerposten; ein daraus freigegebenes Angebot ist an den Lagerposten gebunden — die Stückzahl ist auf den Bestand begrenzt (bei Freigabe und Bearbeitung geprüft), sinkt bei Ausbuchungen/Umbuchungen automatisch mit und das Angebot verschwindet mit dem letzten Stück, genau wie bei Material-Angeboten. Frei angegebene Item-Angebote ohne Lagerbezug bleiben weiterhin möglich (in der Oberfläche mengenfest — anzupassen über Deaktivieren und neu Einstellen). Bestandsgedeckte Item-Angebote sind jetzt bearbeitbar (die Stückzahl, begrenzt auf den Lagerbestand); zuvor lief die Bearbeitung eines Item-Angebots ins Leere (REQ-MARKET-014, Migration V221, ADR-0108).

- **Monitoring: Das Containers-Dashboard zeigt neben „CPU Throttled Seconds" jetzt „CPU Throttled Period Ratio %".** Das neue Panel bildet den Anteil der gedrosselten CFS-Perioden ab (die latenzrelevante Kennzahl statt der reinen Drossel-Sekunden) und markiert mit einer roten 25%-Linie die Schwelle des Alerts `ContainerCpuThrottledHigh`, sodass echtes CPU-Throttling auf einen Blick von harmlosen Burst-Spitzen unterscheidbar ist (REQ-OBS-014).

### Changed

- **Aufträge: Bei der Item-Übergabe wird jetzt der dem Auftrag zugeordnete Item-Bestand automatisch abgebucht.** Eine Lieferung verbraucht so viele Stück aus dem zugeordneten Lager-Bestand, wie geliefert wurden (höchstens den zugeordneten Bestand, älteste Zeilen zuerst), sodass der bei der Herstellung eingelagerte Bestand nach der Lieferung wieder verschwindet. Fehlt zugeordneter Bestand (etwa bei älteren, vor der Bestandsführung hergestellten Positionen), wird die Lieferung trotzdem gebucht — die Übergabe wird nie blockiert. Ist eine so abgebuchte Bestandszeile in der Materialbörse angeboten, wird die Anzeige automatisch auf den verbleibenden Bestand nachgezogen (bzw. mit einer geleerten Zeile entfernt). Die Abbuchung erscheint bei anderen Betrachtern live im „Item-Bestand"-Panel und im Lager (REQ-ORDERS-030).

- **Aufträge: Bei öffentlichen SK-Aufträgen sehen Mitglieder der bestellenden Einheit Besitzer und Standort des zugeordneten Item-/Material-Bestands nicht mehr.** Auf dem Item-Bestand-Panel, der Materialsammlung und den Inventar-Detaillisten eines Auftrags werden Besitzer und Standort für Betrachter der Auftraggeber-Seite ausgeblendet („—"); Mengen, Liefer- und Herstellungsstand bleiben sichtbar. Mitglieder der bearbeitenden Einheit und Admins sehen weiterhin alles; bei staffel-eigenen Aufträgen ändert sich nichts (REQ-ORDERS-029, ADR-0107).

- **Material- und Ortsauswahl laufen jetzt überall über eine durchsuchbare Combobox mit Server-Suche statt über ein einfaches Dropdown.** Betroffen sind das Einbuchen-Formular (Material + Ort), die Materialzeilen beim Anlegen und Bearbeiten von Aufträgen, die Eingangsmaterial-Auswahl beim Anlegen und Bearbeiten von Raffinerieaufträgen, der Ziel-Ort im Umbuchen-Dialog, die Materialnavigation der Lager-Detailseite und die Admin-Materialaliasse. Tippen sucht wie bei den Nutzer- und Item-Suchfeldern direkt auf dem Server — dadurch bleibt jeder Eintrag unabhängig von der Kataloggröße auffindbar und die Seiten müssen den Katalog nicht mehr komplett einbetten (REQ-FE-016, ADR-0100).

- **Bank: Die Konto-Auswahlfelder (Zielkonto einer Buchung, Quellkonto der Direktbuchung, Konto einer Berechtigung sowie der Kontofilter) sind jetzt server-seitige Suchfelder, und die Kontenverwaltung ist echt paginiert.** Vorher wurde die Kontoliste bei 500 Konten stillschweigend abgeschnitten — ein Transfer-Ziel, ein Berechtigungskonto oder ein verwaltetes Konto darüber hinaus war ohne Suche, Seitenblätterung oder Hinweis nicht mehr erreichbar. Die Picker holen passende aktive Konten jetzt beim Tippen nach (Nummer oder Name), und die Verwaltungstabelle blättert seitenweise, sodass bei den für ~5000 Mitglieder geplanten Kontozahlen jedes Konto erreichbar bleibt (REQ-BANK-053, REQ-FE-017).

- **Lager: Beim Umbuchen ist das „Buchen in OrgUnit"-Dropdown jetzt immer sichtbar, sobald der Ziel-Eigentümer mindestens einer Org-Einheit angehört, und auf die aktuelle Einheit der Zeile vorbelegt.** Es listet die Einheiten des ausgewählten Eigentümers über alle vier Ebenen (Staffel, Spezialkommando, Bereich, Organisationsleitung) — vorher erschienen nur Staffel/SK, und der Picker blieb bei genau einer Einheit ganz verborgen. Da nun immer eine konkrete Einheit vorausgewählt ist, behält ein Umbuchen ohne Änderung des Dropdowns die bisherige Einheit bei (REQ-INV-007, #1328).

- **Material-Übersicht (Preis-Matrix): Die Filter (Material, System, Loading Dock, Auto Load) wirken jetzt serverseitig statt nur über die im Browser geladenen Zeilen.** Ein Filterwechsel fragt die passende Teilmenge direkt beim Backend an, statt die komplette Material×Terminal-Matrix in den Browser zu laden und dort zu filtern — ein gefiltertes Raster zeigt entsprechend nur noch Terminals und Materialien mit einem Preistreffer in der Auswahl (REQ-UI-014, ADR-0105).

### Fixed

- **Materialbörse: Die Item-Suche im „Material anbieten"-Dialog findet Item-Lagerposten mit mehrwortigen Namen wieder.** Das Frontend kodierte den Suchbegriff auf dem Weg zum Backend doppelt (ein Leerzeichen wurde zu `%2520` statt `%20`), sodass die getippte Suche nach einem Item-Bestand wie „E2E Boerse Item Stock Widget" nichts fand, während das Durchstöbern ohne Suchbegriff die Zeile zeigte. Der Freigabe- und der Item-Picker sowie die Angebotssuche übergeben den Suchbegriff jetzt einmalig kodiert als URI-Variable (REQ-MARKET-002/014).

- **Combobox-Suchen finden mehrwortige und Umlaut-Begriffe wieder: Die Nutzer-, Bankkonto-, Mitglieder-, Item- und Hangar-Suche im Frontend-Proxy waren doppelt URL-kodiert.** Ein getippter Suchbegriff mit Leerzeichen oder Umlaut (z. B. „John Doe" in der Nutzer-Combobox) wurde auf dem Weg zum Backend zweimal kodiert und traf dort verstümmelt ein, sodass die Suche nichts fand; einwortige Begriffe waren nicht betroffen. Der Begriff wird jetzt genau einmal kodiert (REQ-FE-016/017).

- **Suche: Mehrwortige und umlauthaltige Suchbegriffe finden jetzt wieder Treffer in Operationen, Blueprints und Standard-Blueprints, dem UEX-Ortstypeahead sowie dem persönlichen und dem Admin-Item-Inventar.** Die betroffenen Frontend-Proxys kodierten den Freitext doppelt (`URLEncoder` plus erneute WebClient-Kodierung), sodass etwa „Müller" beim Backend als `M%C3%BC…` ankam und die Suche leer lief; der Begriff wird jetzt als WebClient-URI-Template-Variable genau einmal kodiert (REQ-FE-016).

- **Admin (Persönliche Blueprints): Der Filter über die Blueprints eines ausgewählten Nutzers findet mehrwortige und umlauthaltige Begriffe wieder.** Das Frontend kodierte den Suchbegriff auf dem Weg zum Backend doppelt (die `enc(...)`-Hilfsmethode plus erneute WebClient-Kodierung), sodass etwa „Größe Röhre" verstümmelt ankam und nichts fand; der Begriff wird jetzt genau einmal als WebClient-URI-Template-Variable kodiert (REQ-FE-016).

- **Lager: Die Material- und die Item-Detailseite (`/inventory/material/{id}`, `/inventory/game-item/{id}`) blättern jetzt serverseitig durch alle Bestandszeilen statt bei 1000 Zeilen still abzuschneiden.** Bislang wurde nur eine feste Seite von 1000 Einträgen geladen und ohne Blättern angezeigt — bei mehr Zeilen waren die restlichen unsichtbar und unerreichbar. Beide Ansichten haben nun eine Seitensteuerung mit Seitengrößen 50/100/200, die die Ergebnisliste ohne Neuladen austauscht (REQ-INV-033, ADR-0104).

- **Material-Detailseite: Die Preisliste zeigt jetzt garantiert alle Terminals, die das Material handeln.** Bisher wurde nur die erste Seite (max. 1000 Terminals) geladen und als vollständige Liste dargestellt, sodass alphabetisch spätere Terminals unbemerkt fehlten; die Liste wird nun seitenweise vollständig zusammengesetzt (REQ-UI-015, ADR-0105).

- **Admin: Die Katalog-Seiten (Materialien, Orte, Missionsdaten, Spezialkommandos, Systemeinstellungen, UEX-Daten, Schiffsdaten) zeigen jetzt garantiert alle Einträge statt nur der ersten 1000 bzw. 10000.** Die Controller laden den Katalog seitenweise vollständig; die UEX-Zusammenfassungs-Chips zählen über die vom Backend gemeldete Gesamtzahl, und sollte das Sicherheitslimit beim Laden je erreicht werden, erscheint ein deutlicher Warnhinweis statt einer stillschweigend unvollständigen Liste (REQ-ADMIN-001/002, ADR-0102).

- **Die gecachten Referenzkataloge des Frontends (Staffel-/SK-Umschalter in der Seitenleiste, Material-, Orts-, Schiffstyp- und Terminal-Auswahlen, Materialpreis-Matrix) laden jetzt ebenfalls garantiert alle Einträge statt einer einzelnen begrenzten Seite.** Der Cache holt solche Kataloge seitenweise vollständig, bevor er sie ablegt — ein über die bisherige Grenze hinaus gewachsener Katalog kann dadurch nicht mehr app-weit still abgeschnitten ausgeliefert werden (REQ-ADMIN-003, ADR-0103).

- **Beförderung: Die Bewertungsmatrix in der Bewertungsverwaltung zeigt jetzt garantiert alle Mitglieder und alle Bewertungen.** Bewertungen wachsen multiplikativ (Mitglieder × Kategorien); bisher wurden Mitglieder und Bewertungen in je einer begrenzten Anfrage geladen, sodass jenseits der Grenze Zellen fehlten und wie „noch nicht bewertet" aussahen. Beide Achsen werden nun seitenweise vollständig geladen; sollte das Sicherheitslimit je greifen, erscheint ein deutlicher Warnhinweis statt einer stillschweigend lückenhaften Matrix (REQ-PROMO-001, ADR-0102).

- **Benachrichtigungen: Die Benachrichtigungsseite deckelt die Liste nicht mehr stillschweigend bei den neuesten 50.** Bei mehr als 50 Benachrichtigungen zeigt die Seite jetzt einen Hinweis „neueste N von M" und eine „Mehr laden"-Schaltfläche, die die nächste Seite direkt nachlädt, statt die neuesten 50 als vollständigen Posteingang auszugeben (REQ-NOTIF-019).

- **Mein Inventar: Die Standortsuche weist im Durchstöbern-Modus jetzt auf weitere Treffer hin.** Bei leerer Eingabe liefert die UEX-Standortsuche bis zu 2000 Orte; füllt eine Antwort diese Grenze, hängt die Liste nun den Hinweis „Weitere Treffer vorhanden – Suche verfeinern" an, statt die gekappte Liste als vollständig darzustellen (REQ-FE-016).

- **Aufträge: Der scmdb.net-Import füllt die Mengenfelder wieder zuverlässig.** Der Import suchte die Mengenfelder noch als `input[type="number"]`, obwohl sie seit der SCU-Dezimal-Umstellung Textfelder sind — gefundene Materialien wurden dadurch ohne Menge eingetragen und der Import brach still ab.

- **Lager: Der Umbuchen- und der Ausbuchen-Dialog werden wieder mittig im Fenster angezeigt statt am oberen Rand zu kleben.** Die Dialoge wurden per Inline-`display:block` geöffnet, was die zentrierende Flex-Ausrichtung von `.modal` überschrieb; sie öffnen jetzt mit `display:flex` (#1328).

- **Lager: Das „Buchen in OrgUnit"-Dropdown im Umbuchen-Dialog wird jetzt wirklich angezeigt.** Die Einheiten-Abfrage lief gegen einen Backend-Pfad, den das Frontend nie beantwortet (404) — der Picker blieb dadurch immer verborgen, und ein Umbuchen auf einen Eigentümer mit mehreren Einheiten schlug fehl. Die Abfrage läuft jetzt wie beim Bank-Gegenpartei-Picker über den Frontend-Proxy (REQ-INV-007, #1328).

## [v1.4.5](https://github.com/krt-profit/basetool/releases/tag/v1.4.5) - 2026-07-15

### Fixed

- **Lager: Das „+ Zuordnen"-Popover einer Lagerzeile am unteren Bildschirmrand klappt jetzt nach oben auf, statt unten aus dem Sichtbereich zu rutschen.** Seit der Umstellung auf feste Positionierung (v1.4.3) konnten Mengenfeld und „Speichern"-Button unter den Bildschirmrand fallen und waren dort nicht erreichbar (ein festes Element lässt sich nicht in den Sichtbereich scrollen); das Popover flippt nun wie die Suchfeld-Dropdowns über den Auslöser (REQ-UI-011, REQ-INV-027).

## [v1.4.3](https://github.com/krt-profit/basetool/releases/tag/v1.4.3) - 2026-07-15

### Added

- **Aufträge: Beim Erfassen einer Herstellung lässt sich je Material festlegen, dass es nicht ausgebucht werden soll.** Der Herstellen-Dialog hat jetzt pro Material eine Checkbox „Nicht ausbuchen": Ist sie gesetzt, wird dieses Material von der Lagerentnahme ausgenommen — die Herstellung wird gebucht, der verknüpfte Lagerbestand des Materials bleibt aber unverändert (REQ-ORDERS-025).

### Changed

- **Aufträge: Die aggregierten Materialien eines Item-Auftrags zeigen jetzt nur noch den offenen Bedarf für die noch nicht hergestellten Einheiten.** Jede Herstellungsbuchung verringert den aggregierten Bedarf (und die Kennzahl „Offene Menge") anteilig — unabhängig davon, ob das Material ausgebucht oder als „Nicht ausbuchen" markiert wurde; eine vollständig hergestellte Position trägt 0 bei. Die Eintragungen-Zielmenge (Material-Claims) bleibt bewusst auf dem vollen Auftragsbedarf (REQ-ORDERS-025).

### Fixed

- **Aufträge: Die Detailseite eines Item-Auftrags erzeugt keine unnötigen Zugriffe mehr auf die mitgliederinterne Blaupausen-Abdeckung.** Die Abdeckungs-Ansicht wurde bei jedem Abschnitts-Nachladen (Kopf, Positionen, Kennzahlen, …) erneut vom Backend geholt, obwohl sie nur auf der Vollseite bzw. beim eigenen Nachladen des Blaupausen-Abschnitts angezeigt wird. Für Nicht-Mitglieder der bearbeitenden Staffel/SK antwortete der mitgliederinterne Endpunkt jedes Mal mit 403 und flutete das Backend-Log mit `ACCESS_DENIED`-Warnungen; der Abruf ist jetzt auf die beiden Renderings beschränkt, die die Daten tatsächlich anzeigen (REQ-ORDERS-016).

- **Lager: Das „Auftrag"- und „Einsatz"-Dropdown der Zuordnung wird nicht mehr abgeschnitten, wenn es unten aus der Tabelle herausragt.** Das „+ Zuordnen"-Popover wurde vom horizontal scrollenden Tabellencontainer am unteren Rand beschnitten (in Firefox wie Chrome sichtbar als Unterbrechung); es wird jetzt wie die Suchfeld-Dropdowns fest am Auslöser verankert und überlagert den Rand vollständig (REQ-UI-011, REQ-INV-027).

## [v1.4.2](https://github.com/krt-profit/basetool/releases/tag/v1.4.2) - 2026-07-15

### Changed

- **Aufträge: Die Herstellung ist in den Reiter „Bestellte Items" integriert; der eigene Herstellung-Reiter entfällt.** Der Button „Herstellung erfassen" steht jetzt als letzte Spalte je Position im Reiter „Bestellte Items", die Bauplan-Spalte entfällt. Der Materialbedarf je Stück steht dort in einer per Pfeil ausklappbaren Unterzeile (standardmäßig eingeklappt, analog zu Notiz/Begründung in der Bank-Antragstabelle). Zusätzlich haben „Hergestellt" und „Geliefert" nun jeweils einen Fortschrittsbalken (REQ-ORDERS-025, REQ-ORDERS-026).

- **Aufträge: Der Reiter „Item-Übergaben" zeigt „Alle Items wurden vollständig übergeben" nur noch, wenn der Auftrag wirklich vollständig ausgeliefert ist.** Ist noch nichts zur Übergabe hergestellt (oder es fehlt noch Herstellung), weist der Reiter jetzt darauf hin, dass zuerst die Herstellung zu erfassen ist — statt der irreführenden Vollständig-Meldung (REQ-ORDERS-025).

### Fixed

- **Lager: Die Checkbox „Mit vorhandenem Bestand zusammenführen" im Einbuchen-Dialog ist wieder normal groß.** Eine seitenweite Formatregel streckte die Checkbox auf volle Breite mit großem Innenabstand; sie nimmt jetzt wie die übrigen Kontrollkästchen die Standardgröße an (betrifft auch die Checkbox „Als persönlicher Eintrag markieren").

## [v1.4.1](https://github.com/krt-profit/basetool/releases/tag/v1.4.1) - 2026-07-15

### Added

- **Aufträge: Neue Herstellung-Funktion für Item-Aufträge.** Für einen Item-Auftrag lassen sich jetzt die bereits hergestellten Einheiten erfassen; die Buchung verbraucht dabei den verknüpften Lagerbestand (die für die Menge benötigten Materialien werden aus den zugeordneten Lagereinträgen abgezogen). Die Auslieferung eines Item-Auftrags setzt nun eine vorherige Herstellung voraus — es kann höchstens so viel ausgeliefert werden, wie hergestellt wurde (REQ-ORDERS-025).

### Changed

- **Aufträge: Die Auftragsdetailseite ist neu gestaltet — gegliedert in Reiter und ein Kennzahlen-Band.** Die zuvor untereinander gestapelten Abschnitte sind jetzt auf Reiter verteilt, und die wichtigsten Auftragskennzahlen (u. a. Fortschritt aus hergestellter/ausgelieferter Menge) stehen kompakt in einem Kennzahlen-Band am Seitenkopf (REQ-ORDERS-026).

- **Aufträge-Übersicht: Die Materialliste je Auftrag ist jetzt ein- und ausklappbar** (standardmäßig eingeklappt); der Zustand wird pro Nutzer lokal im Browser gespeichert und bleibt über Filterwechsel und Neuladen erhalten (REQ-ORDERS-027).

- **Aufträge-Übersicht: Der Staffel-Filter ist jetzt eine Mehrfachauswahl.** Statt nur „Eigene Staffel / Alle Staffeln" lassen sich per Auswahl-Dropdown gezielt die anzuzeigenden Staffeln ankreuzen (alle aktiven Staffeln, standardmäßig alle ausgewählt). Der Filterzustand wird lokal beim Nutzer gespeichert (REQ-ORDERS-027).

### Fixed

- **Lager: Bei Materialien mit Mengentyp „Stück" werden Mengen jetzt überall als ganze Zahlen ohne Nachkommastellen angezeigt.** In den Auftrags- und Einsatz-Chips, den Rest-Chips, den Herkunft-Pickern beim Aus- und Umbuchen sowie im Übergabe-Dialog stand bisher z. B. „5,000" statt „5". Die drei Nachkommastellen bleiben SCU-Materialien vorbehalten (REQ-INV-027).

- **Monitoring: Der kritische Alarm `ContainerRestartLoop` schlägt nicht mehr fälschlich bei einem einzelnen Container-Neustart (etwa einem regulären Deploy von backend/frontend/ingest) an.** Er zählte Neustarts mit `increase()` auf der Metrik `container_start_time_seconds` — einer Gauge mit dem Unix-Startzeitpunkt, deren Differenz bei jedem Neustart die Sekunden/Tage zwischen altem und neuem Start ergab und so schon ab dem ersten Neustart über den Schwellwert 3 sprang. Er nutzt jetzt `changes()` (echte Neustart-Zählung, wie das Grafana-Panel) und meldet erst ab mehr als drei Neustarts in 15 Minuten; abgesichert durch einen promtool-Test (REQ-OBS-014).

- **Monitoring: Der Alarm `FrontendLoginBroken` schlägt nicht mehr fälschlich bei Scanner-/Bot-Zugriffen auf den OAuth-Callback an.** Ein leerer oder unvollständiger Aufruf von `/login/oauth2/code/*` wirft in Spring Security `invalid_request` (noch vor jedem Token-Austausch); dieser Code floss bisher in den `provider_error`-Zähler und konnte den Alarm in verkehrsarmen Zeiten ohne echten Ausfall auslösen. `invalid_request` wird jetzt wie die State-Fehler dem harmlosen `invalid_state`-Bereich zugeordnet, sodass `provider_error` nur noch echte Token-/IdP-Brüche zählt (REQ-OBS-011).

- **Monitoring: Der kritische Alarm `PostgresFatalOrPanic` schlägt nicht mehr bei harmlosen FATAL-Zeilen eines Datenbank-Neustarts an.** `PANIC` löst weiterhin bedingungslos aus; bei `FATAL` werden die bekannten harmlosen Lebenszyklus-Meldungen (Datenbank startet gerade / fährt herunter / im Wiederherstellungsmodus / Verbindung durch Admin-Befehl beendet) ausgenommen, während sicherheitsrelevante FATALs (fehlgeschlagene Authentifizierung, unbekannte Rolle/Datenbank, zu viele Clients) weiterhin melden.

- **Monitoring: Zusammenhängende Ausfälle erzeugen jetzt deutlich weniger Alarm-Mails.** Alertmanager unterdrückt die Ressourcen-Warnungen (`ContainerWorkingSetHigh` u. a.) eines neustartenden Containers unter dessen `ContainerRestartLoop` und die von einem ausgefallenen Scrape-Ziel abgeleiteten Warnungen unter dessen `TargetDown`; zudem werden Benachrichtigungen nur noch nach `alertname` gruppiert, sodass ein mehrere Ziele betreffender `TargetDown` in einer Mail zusammengefasst wird (REQ-OBS-014).

- **Monitoring: Alloy-Speicherlimit von 256M auf 384M angehoben und `GOMEMLIMIT` von 230MiB auf 300MiB gesenkt, um die wiederkehrenden `ContainerWorkingSetHigh`-Warnungen für Alloy zu beheben.** `GOMEMLIMIT` lag bei ~90 % des Limits — genau auf der Alarmschwelle — und ADR-0095 (zusätzlicher App-stdout-Versand) hatte den Arbeitsspeicher erhöht, ohne das Limit anzupassen. Der neue `GOMEMLIMIT` (~78 %) liegt bewusst unter der Schwelle. Greift beim nächsten Monitoring-Deploy (Alloy wird neu erstellt).

## [v1.4.0](https://github.com/krt-profit/basetool/releases/tag/v1.4.0) - 2026-07-14

### Changed

- **Lager: Ein Lagereintrag kann jetzt mehreren Aufträgen und Einsätzen mit eigener Menge zugeordnet werden.** Statt genau einem Auftrag und einem Einsatz lässt sich der Bestand eines Eintrags getrennt je Dimension aufteilen und wird als Mengen-Chips direkt am Eintrag angezeigt (z. B. 60 SCU für Auftrag A, 40 SCU für Auftrag B); ein unverteilter Rest erscheint als eigener Chip. Auch beim Einbuchen kann die Menge gleich auf mehrere Aufträge/Einsätze verteilt werden. Ein Eintrag stapelt sich dadurch nur noch über seine physische Identität (Material, Besitzer, Lagerort, Qualität, Org-Einheit) — die Zuordnungen wandern an den einzelnen Eintrag (REQ-INV-027).

- **Lager: „Geliefert" ist jetzt je Auftrag statt je Eintrag.** Ein Eintrag, der mehrere Aufträge bedient, kann für den einen als geliefert und für den anderen als offen markiert sein. Die Materialsammlung eines Auftrags zeigt zudem die tatsächlich diesem Auftrag zugeordnete Menge statt des gesamten Bestands des Eintrags (REQ-INV-027).

- **Lager: Beim Verkauf von einem Einsatz zugeordnetem Bestand wählt der Verkäufer, welchen Einsätzen wie viel des Erlöses gutgeschrieben wird.** Bisher wurde der gesamte Erlös automatisch einem Einsatz zugeschrieben; jetzt verteilt der Verkäufer den Erlös auf die Einsätze, an denen er teilnimmt, und ein nicht zugeordneter Rest bleibt persönlich (REQ-INV-027).

- **Lager: Beim Ausbuchen und Umbuchen wählt man jetzt direkt im Dialog, von welchen Auftrags- und Einsatz-Marken die Menge abgezogen wird.** Je zugeordneter Marke gibt es ein Mengenfeld; nicht zugewiesene Mengen kommen vom noch nicht zugewiesenen Rest (Voreinstellung), und reicht der Rest nicht, blockiert der Dialog das Absenden und nennt die mindestens auf Marken zu verteilende Menge. Beim Umbuchen nimmt die verschobene Menge ihre Marken mit; beim Verkauf zeigt der Dialog den je Einsatz gekoppelten Erlös (REQ-INV-027).

- **Lager: Inventaränderungen erscheinen jetzt live in allen Lager-Ansichten anderer Betrachter.** Fügt jemand eine Zuordnung hinzu/ändert/entfernt sie, bucht aus, bucht um oder leert das Lager, aktualisieren sich die geteilte Übersicht (`/inventory/all`), die Materialübersicht (`/inventory`), die Material-Detailansicht und „Mein Lager" bei den anderen Betrachtern ohne manuelles Neuladen — die eigene Filter- und Baum-Ansicht bleibt erhalten. Zusätzlich spiegeln die Auftrags-Materialsammlung (die zugeordnete Menge) und die Materialbörse (Angebote, die bei sinkendem Bestand automatisch schrumpfen) solche Inventaränderungen jetzt live. Bisher sahen die Betrachter veraltete Daten bis zum nächsten Reload (REQ-FE-010).

- **Lager: Die aggregierte Materialübersicht zeigt jetzt auch die maximale verfügbare Qualität.** In der Ansicht `/inventory` steht zwischen „Ø Qualität" und „Gesamtmenge" eine neue Spalte „Max. Qualität" mit der höchsten für das jeweilige Material vorhandenen Qualität (REQ-INV-027).

- **Einsatz-Detailseite: Die Lagerbestände zeigen jetzt alle zugeordneten Aufträge als Mengen-Chips.** In der Auftrag-Spalte der Lagereinträge-Tabelle stand bisher nur der erste zugeordnete Auftrag; bei einem auf mehrere Aufträge aufgeteilten Eintrag werden nun alle Aufträge mit ihrer jeweiligen Menge angezeigt (REQ-INV-027).

- **Oberfläche: Das dezente Wabenmuster (Honeycomb) im Seitenhintergrund entfällt.** Der Hintergrund der App sowie der Keycloak-Anmelde- und -Kontoseiten ist jetzt durchgehend flaches Schwarz; der dezente orange Schimmer oben auf der Anmeldeseite bleibt erhalten. Das Hintergrundbild `honeycomb-bg.svg` wurde aus allen Ressourcen entfernt (REQ-UI-003).

- **Lager: Neuer Filter „Nur nicht-persönliche Einträge" in „Mein Lager".** Neben „Nur persönliche Einträge" lässt sich die Ansicht jetzt auch umgekehrt auf die geteilten (nicht-persönlichen) Bestände einschränken. Beide Filter schließen sich gegenseitig aus (das Aktivieren des einen hebt den anderen auf) und werden wie die übrigen Filter in der Seiten-URL mitgeführt (REQ-INV-006).

- **Lager: Stückzahl-Materialien werden wieder zu einem Lagereintrag zusammengeführt.** Gleichartige Bestände desselben Stückzahl-Materials (gleiche Qualität, Lagerort und Org-Einheit) landen beim Einbuchen, Ändern und Umbuchen automatisch in einem einzigen Eintrag, wobei vorhandene Notizen zusammengeführt und die Auftrags-/Einsatz-Zuordnungen der Einträge vereint werden; bei SCU-Materialien lässt sich das Zusammenführen pro Aktion optional über eine Checkbox im Dialog aktivieren. Angebote in der Materialbörse bleiben dabei unverändert (die angebotene Menge steigt nicht), und bereits vorhandene passende Einträge werden beim Ausrollen der Änderung einmalig zusammengeführt.

- **Materialbörse: Angebote werden automatisch verringert, wenn der zugehörige Lagerbestand kleiner wird.** Wird ein Lagereintrag ausgebucht, umgebucht, übergeben oder verringert und die angebotene Menge ist nicht mehr gedeckt, sinkt die angebotene Menge des Angebots dauerhaft auf den verbleibenden Bestand. Erhöht sich der Bestand später wieder, bleibt das Angebot unverändert — mehr anzubieten ist eine bewusste Entscheidung des Anbieters.

- **Organigramm: Kopf-Kasten und Bereichsleiter optisch geschärft.** Der Kasten der Organisationsleitung ist nicht mehr auf die Rasterbreite fixiert, wächst auf seine Beschriftung und hat mehr Innenabstand, sodass „ORGANISATIONSLEITUNG" nicht mehr am Rand klebt; der Rahmen der Bereichsleiter ist einen Pixel kräftiger.

- **Organigramm: Ansicht entrümpelt.** Die Markierung „Leitung" an kontogebundenen Sitzen entfällt (der schreibgeschützte Zustand zeigt sich im Bearbeitungsmodus bereits an den fehlenden Schaltflächen), und der Pflegehinweis „Kein Account" erscheint nur noch im Bearbeitungsmodus (REQ-ORG-010, REQ-ORG-013, REQ-ORG-020).

- **Organigramm: Die Mitglieder der Organisationsleitung stehen jetzt nebeneinander.** Statt als senkrechte Kette unter dem OL-Kasten fächern sie nun waagerecht auf — dieselbe Anordnung wie die Staffeln/Spezialkommandos unter einem Staffelleiter (REQ-ORG-013).

- **Organigramm: Der horizontale Scrollbalken bleibt bei einem breiten Diagramm immer sichtbar.** Bisher saß er am unteren Rand des (bei vielen Einheiten sehr hohen) Diagramms — oft unterhalb der Fußzeile, sodass man erst die ganze Seite herunterscrollen musste, um seitlich zu scrollen. Ein mitlaufender Scrollbalken ist jetzt knapp über der Fußzeile fixiert und immer erreichbar.

- **Organigramm/Leitung: Beim Besetzen eines Postens mit einem Account wird ein bestehender gleichnamiger Freitext-Eintrag automatisch übernommen.** Bisher blieb ein zuvor eingetragener Freitext-Name (z. B. ein Platzhalter für ein Mitglied ohne Account) nach der Ernennung des Accounts als Dublette stehen und musste von Hand gelöscht werden. Wird jetzt in „Leitung" ein Account auf einen Posten (OL-Mitglied, SK-Leiter, Bereichskoordinator/-operator, Ensign) ernannt und existiert dort ein Freitext-Eintrag mit demselben Namen, wird dieser automatisch in den Account umgewandelt statt zusätzlich stehen zu bleiben. Die Einzelposten (Staffelleiter, Bereichsleiter, Kommandoleiter, Stv., Grand Admiral) taten dies bereits.

- **Neuer Posten „Grand Admiral" in der Organisationsleitung.** Ein einzelnes OL-Mitglied kann zum Grand Admiral ernannt werden; im Organigramm steht es dann direkt unter der Kachel „Organisationsleitung", über den übrigen OL-Mitgliedern. Der Grand Admiral hat exakt die Rechte eines OL-Mitglieds (kein neues Rechtemodell). Ernannt wird er unter „Organisation → Leitung" (Admin): wer noch kein OL-Mitglied ist, wird dabei automatisch eines; beim Entfernen bleibt die Person normales OL-Mitglied. Alternativ lässt sich – wie bei den anderen Feldern im Organigramm – ein Freitext-Name für einen Grand Admiral ohne Account direkt im Organigramm-Editor eintragen (verleiht keine Rechte). Es gibt immer höchstens einen Grand Admiral (REQ-ORG-021).

- **Die Seite „Organisation → Leitung" ist jetzt nur noch für Admins und Officer zugänglich.** Bisher öffnete das Gate zusätzlich für reine Logistiker/Missionsmanager, die dort aber ohnehin keine Ernennungsrechte und damit eine leere Seite hatten. Zugang (Seite und Aktionen) ist jetzt auf `ADMIN`/`OFFICER` eingegrenzt; alle funktionalen Leiter (OL-, Bereichs-, SK- und Staffelleitung samt Kommandorängen) tragen die operative `OFFICER`-Rolle und behalten den Zugang unverändert (REQ-ROLE-004).

### Fixed

- **Aufträge: Die Teil-Übergabe eines Lagereintrags, der gleichzeitig einem Auftrag und einem Einsatz zugeordnet ist, schlägt nicht mehr fehl.** Die übergebene Menge verlässt beide Markierungen; die Einsatz-Markierung wird automatisch auf den verbleibenden Bestand gekappt (Rest zuerst, dann anteilig). Ist die Aufteilung mehrdeutig (mehrere Einsätze und der Rest deckt die Menge nicht), lässt der Übergabe-Dialog dich direkt wählen, wie viel von welchem Einsatz abgezogen wird. Außerdem behoben: schnelle aufeinanderfolgende Chip-Änderungen am selben Eintrag lösten fälschlich einen Konflikt-Neuladen aus, und ein N+1-Ladeproblem der Zuordnungs-Referenzen (REQ-INV-027, REQ-DATA-003, REQ-FE-003).

- **Aufträge: Eine Auftrags-Übergabe kann jetzt höchstens die dem Auftrag zugeordnete Menge eines Lagereintrags hergeben.** Bei einem Eintrag, dessen Bestand auf mehrere Aufträge aufgeteilt ist, ließ sich bisher mehr für einen Auftrag übergeben, als ihm zugeordnet war — der Überschuss ging still zulasten der anderen Aufträge bzw. des freien Rests und verletzte deren Deckung. Das Mengenfeld ist jetzt auf die dem Auftrag zugeordnete Menge begrenzt, und das Backend lehnt eine zu große Menge mit HTTP 400 ab (REQ-INV-027).

- **Lager: Bei Materialien mit Mengentyp „Stück" werden Mengen jetzt überall als ganze Zahlen ohne Nachkommastellen angezeigt.** In den Auftrags- und Einsatz-Chips, den Rest-Chips, den Herkunft-Pickern beim Aus- und Umbuchen sowie im Übergabe-Dialog stand bisher z. B. „5,000" statt „5". Die drei Nachkommastellen bleiben SCU-Materialien vorbehalten (REQ-INV-027).

- **Aufträge: Die „Geliefert"-Umschaltung auf der Material-Sammlungs-Seite erscheint jetzt live bei anderen Betrachtern.** Setzt jemand auf `/orders/{id}/material-collection` einen Lagereintrag auf geliefert oder verschiebt ihn (Besitzer/Standort), aktualisiert sich die Tabelle bei anderen Betrachtern derselben Seite sowie die Materialsammlung auf der Auftrag-Detailseite jetzt ohne manuelles Neuladen; bisher blieben sie bis zum Reload veraltet (REQ-FE-010).

- **Barrierefreiheit: Gedämpfter grauer Text erfüllt auf dem flachen schwarzen Hintergrund wieder den WCAG-AA-Kontrast.** Mit dem Wegfall des Wabenmusters lag gedämpfter Text in Grau 2 (`#646464`) nur noch bei rund 3,5:1 und unterschritt damit die AA-Schwelle für kleinen Text (sichtbar u. a. auf der Hangar-Seite). Muted-Text, Platzhalter und die leise Löschen-Schaltfläche nutzen jetzt den helleren Ton `--color-gray-2-text` (`#8A8A8A`, rund 6,1:1); Rahmen, Bildlaufleisten und dekorative Symbole behalten das bisherige Grau 2 (REQ-UI-003, REQ-UI-006).

- **Keycloak-Nutzerabgleich: Der nächtliche Abgleich lief ins Leere, wenn dem Dienstkonto die Berechtigung `view-realm` fehlte.** Seit der rollenindizierten Auflösung (5000-Konten-Härtung) liest der Abgleich die Realm-Rollen und deren Mitglieder, was zusätzlich zu `view-users` die `realm-management`-Rolle `view-realm` erfordert. Fehlt sie, brach jeder Lauf mit `403` ab — der Abgleich wird übersprungen (kein Datenverlust), aber ausgetretene Mitglieder behielten ihre Rollen und Rollenänderungen wurden nur beim interaktiven Login übernommen. Der Fehler wird jetzt mit klarem Hinweis auf die fehlende Rolle protokolliert; die nötige Berechtigung ist in `docs/keycloak/README.md` dokumentiert (REQ-SEC-018).

- **Logging: Unauthentifizierte Backend-Anfragen (HTTP 401) werden nicht mehr als WARN protokolliert.** Der interne TLS-Health-Probe fragt alle 30 Sekunden die Wurzel `/` jedes Dienstes ab und erzeugte so rund 2 WARN-Zeilen pro Minute reines Rauschen. Ein 401 ist der erwartete Normalfall für jeden anonymen Aufrufer und läuft jetzt auf DEBUG; 403 und alle anderen 4xx bleiben WARN, und die Metrik `basetool_http_error_total{code}` ist unverändert (REQ-OBS-001).

- **Monitoring: Ein laufender Monitoring-Stack mit nicht gesetztem `IRI_MONITORING_ENABLED` schlägt jetzt Alarm, statt still zu driften.** Bei nicht gesetztem Flag lud `deploy.sh` zwar weiter die Konfigdateien auf die Platte, erzeugte die laufenden Container aber nie neu — Regel- und Scrape-Änderungen erreichten das laufende Prometheus nie, und der dafür gedachte Alarm `PrometheusConfigStale` konnte nicht anschlagen, weil seine Metrik nur im (in diesem Fall deaktivierten) Reconcile-Codepfad geschrieben wird. `deploy.sh` meldet den Zustand jetzt pro Durchlauf als WARN und über die eigenständige Metrik `basetool_monitoring_reconcile_disabled`; der neue Alarm `MonitoringReconcileDisabled` schlägt nach 30 Minuten an (REQ-OBS-014, REQ-OPS-013).

- **Monitoring: Der Alarm `EdgeIpv6Unreachable` schlug fälschlich an, weil der blackbox-Prober kein IPv6 konnte.** Die Monitoring-Netze des Exporters sind rein IPv4, sodass er keine IPv6-Route hatte und die v6-Probes stets scheiterten, obwohl das öffentliche Edge über IPv6 erreichbar ist. Der Exporter bekommt jetzt ein eigenes v6-Egress-Netz (`net-blackbox-v6`), womit die Probes die echte Edge-Strecke testen und der Alarm ein wahres Signal wird (REQ-OBS-008/-012).

- **Monitoring: cAdvisor schreibt keine Dateisystem-Fehlerzeilen mehr ins Log.** Auf dem containerd-`overlayfs`-Speichertreiber von Docker 29 konnte cAdvisor die Layer der einzelnen Container nicht lesen und schrieb pro Housekeeping-Durchlauf eine Fehlerzeile je Container (`could not stat … no such file`), ohne verwertbare Pro-Container-Dateisystemmetriken zu liefern. Die `disk`-Metrikgruppe ist jetzt deaktiviert — sie wird von keinem Alarm oder Dashboard genutzt (die Host-Dateisystembelegung liefert der node-exporter), sodass das Log-Rauschen ohne Funktionsverlust entfällt (REQ-OBS-014).

## [v1.3.6](https://github.com/krt-profit/basetool/releases/tag/v1.3.6) - 2026-07-12

### Changed

- **Kartellbank: Die Benachrichtigung über einen neuen Buchungsantrag verschwindet jetzt, sobald der Antrag bearbeitet ist.** Bank-Mitarbeiter und -Verantwortliche behielten bisher die „Neuer Buchungsantrag"-Benachrichtigung im Postfach, auch nachdem der Antrag längst freigegeben, abgelehnt oder vom Antragsteller zurückgezogen war. Wird ein Antrag entschieden oder zurückgezogen, werden diese nun veralteten Benachrichtigungen jetzt automatisch aus allen betroffenen Postfächern entfernt; Glocke und Liste aktualisieren sich live.

- **Materialbörse: Die Angebotsliste reicht jetzt bis kurz vor die Fußzeile.** Bisher endete die Liste an einer festen Höhe und ließ darunter eine große leere Fläche bis zur Fußzeile. Sie füllt jetzt die verfügbare Höhe bis knapp über die Fußzeile (mit kleinem Abstand zur optischen Trennung) und scrollt bei vielen Angeboten intern.

- **Materialbörse: Die Sortier-Option „Material A–Z" heißt jetzt „Name A–Z".** Die Börse enthält auch Items (nicht nur Materialien); die alphabetische Sortierung greift auf den angezeigten Namen zu, daher der treffendere Name.

### Fixed

- **Kartellbank: Ein zweiter Buchungsantrag lässt sich direkt nach dem ersten wieder bestätigen oder ablehnen.** Nach dem Bestätigen bzw. Ablehnen eines Antrags blieb der „Bestätigen"-/„Ablehnen"-Knopf des nächsten Antrags ohne Wirkung — der Dialog öffnete sich nicht mehr, bis die Seite neu geladen wurde. Der Dialog wurde nach dem Absenden über einen Inline-Stil geschlossen, der das inzwischen klassenbasierte Wieder-Öffnen (CSP-Umstellung, ADR-0093) überstimmte; er schließt jetzt über dieselbe CSS-Klasse und öffnet dadurch zuverlässig erneut (REQ-UI-013, REQ-FE-005).

- **Dieselbe Dialog-Störung an weiteren Stellen behoben.** Die gemeinsamen Öffnen-/Schließen-Handler räumen jetzt einen alten Inline-Anzeigestil beim Öffnen und Schließen weg, sodass jeder Dialog unabhängig davon, wie die Gegenseite ihn ein-/ausblendet, zuverlässig auf- und zugeht. Betraf u. a.: „Operation erstellen" ließ sich nach dem Anlegen einer Operation erst nach Neuladen erneut öffnen, und im Lösch-Bestätigungsdialog einer Operation schloss der „Abbrechen"-Knopf das Fenster nicht (REQ-UI-013).

- **Operationen: Die Fehlermeldung eines nicht ladbaren Abschnitts wird wieder rot dargestellt.** Der Hinweis „Abschnitt konnte nicht geladen werden" trug seit der CSP-Härtung (`style-src-attr 'none'`, ADR-0093) noch einen wirkungslosen Inline-Stil für die Warnfarbe; die Farbe läuft jetzt über eine CSS-Klasse (REQ-UI-013).

- **Monitoring: Die kritischen Alarme `TargetDown` für `blackbox-internal-tls` und `blackbox-http-ipv6` schlagen nicht mehr fälschlich an.** Bei einem Blackbox-`/probe`-Job heißt `up==0` nicht, dass das Ziel unten ist, sondern dass der Prometheus-zu-Exporter-Scrape selbst das Zeitlimit riss — das Blackbox-Modul-Timeout (10 s) war identisch mit dem globalen `scrape_timeout` (10 s), sodass ein bis zur Frist laufender Probe (IPv6 ohne v4-Fallback, langsamer interner TLS-Handshake unter dem gewollten Speicherdruck des Hosts) den Scrape über die Grenze kippte. Jeder `/probe`-Job trägt jetzt ein eigenes `scrape_timeout: 15s`, und `TargetDown` ist auf die Nicht-Probe-Jobs eingegrenzt (der `blackbox-exporter`-Selbst-Scrape und alle App-/Infra-Scrapes bleiben in Reichweite). Ein echter Probe-Ausfall meldet weiterhin sofort über die dedizierten `probe_success`-Alarme (`BlackboxProbeFailed` / `EdgeIpv6Unreachable` / `DnsResolutionFailed` / `CertificateExpiringSoon`) (REQ-OBS-008/-012).

## [v1.3.5](https://github.com/krt-profit/basetool/releases/tag/v1.3.5) - 2026-07-12

### Changed

- **Monitoring: Live-Sync-Abo-Metriken der Auftragsräume klarer benannt.** Im Grafana-Panel „Live-sync subscriptions (live) by topic class" tauchten die Auftrags-Detailseite und die Auftrags-Warteschlange als `order` und `orders` auf — nur durch das Plural-s unterscheidbar und dadurch wie ein versehentliches Duplikat lesbar. Das `topic_class`-Label heißt jetzt `order_detail` bzw. `orders_queue` (analog zu `bank_account`/`bank_staff`). Reine Label-Umbenennung; der Wire-Topic (`order:{id}` / `orders`) bleibt unverändert (REQ-OBS-011).

- **Monitoring: Der Alarm `BankAuditSilenceAnomaly` schlägt erst nach 60 Tagen ohne Bank-Audit-Ereignis an (vorher 5 Tage).** Das Bank-Audit-Volumen ist naturgemäß niedrig, sodass eine mehrtägige Stille normal ist und den Warnalarm bislang fälschlich auslöste. Das breitere 60-Tage-Fenster meldet nur noch eine echte, langanhaltende Bank-Audit-Stille (mögliche REQ-AUDIT-001-Regression).

- **Monitoring: Der Alarm `FrontendLoginBroken` meldet nur noch einen echten Login-Ausfall statt harmloser Fehlversuche.** Bisher schlug er an, sobald es im 15-Minuten-Fenster Login-Fehlversuche, aber keinen erfolgreichen Login gab — bei überwiegend dauerhaft angemeldeten Mitgliedern (30-Tage-Sitzung) sind frische Logins jedoch oft null, während ein einzelner harmloser Fehlversuch (etwa ein Bot am OAuth-Callback, Abbruch oder abgelaufener State → `invalid_state`) genügte, um den Warnalarm auszulösen. Der Alarm wertet jetzt nur wiederholte `provider_error`-Fehler (fehlgeschlagener Code-to-Token-/JWKS-/IdP-Schritt — genau die Bruchstelle, die `KeycloakLoginErrorSpike` nicht sieht) bei gleichzeitig null Erfolgen aus (REQ-OBS-011).

### Fixed

- **Monitoring: Grafana lässt sich wieder neu erzeugen/deployen.** Der Image-Pin zeigte auf `grafana/grafana-oss:13.1.0` — diesen Tag gibt es im OSS-Repository nicht (nur das Enterprise-Repo `grafana/grafana` hat 13.1.0), sodass jeder `--force-recreate` von Grafana mit „not found" abbrach. Zurück auf den neuesten tatsächlich veröffentlichten OSS-Tag `13.0.2` gepinnt.

- **Monitoring: `TempoGeneratorRemoteWriteFailing` nennt jetzt die zweite reale Ursache.** Der Alarm verwies bisher nur auf „Credential-Drift". Tatsächlich kann der Remote-Write auch bei überall korrektem Passwort mit 401 scheitern, wenn `prometheus-web.yml` host-seitig neu provisioniert, der Prometheus-Container aber nicht neu erzeugt wurde (er validiert weiter den alten Hash) — der bekannte Single-File-Bind-Mount-Effekt. Die Alarmbeschreibung führt jetzt Triage-Schritte samt curl-Probe auf und der Fix ist ein `--force-recreate` von Prometheus **und** Tempo (REQ-OBS-014).

- **Monitoring: Der Alarm `SyncZeroItems` schlägt für `uex_sync` nicht mehr fälschlich bei einem unveränderten Katalog an.** Der UEX-Client nutzt bedingte GETs (ETag): Ist der Item-Katalog seit dem letzten Lauf unverändert, liefert jede Kategorie `304 Not Modified`, der Lauf schreibt nichts — für die Metrik bislang nicht von einem echten Leer-Ausfall (leere 200er) unterscheidbar. Der Item-Sync meldet in diesem Fall jetzt die aktuelle Größe des Katalogs statt `0`, sodass der Alarm nur noch bei einem echten leeren UEX-Feed auslöst (REQ-OBS-011, #1041).

- **Monitoring: Loki und Tempo verlieren bei einem Neustart keine frisch empfangenen Log-/Trace-Daten mehr.** Beide dskit-Dienste leeren beim Beenden ihren Write-Ahead-Log (Standard-Drain 30 s), liefen im Monitoring-Compose aber ohne `stop_grace_period` auf Dockers 10-Sekunden-Standard — ein `deploy.sh --force-recreate` (der übliche Weg, eine Config-Änderung zu übernehmen) beendete sie dadurch nach rund 20 s per SIGKILL mitten im Leeren und erzwang eine WAL-Wiederherstellung. Beide erhalten jetzt `stop_grace_period: 45 s`; Prometheus bleibt bewusst beim Standard (absturzsichere WAL-Wiederherstellung), ADR-0072.

- **Monitoring: Der Alarm `SyncZeroItems` schlägt beim SC-Wiki-Sync nicht mehr fälschlich an, wenn der Katalog stabil ist.** Die SC-Wiki-Clients nutzen bedingte GETs (ETag/`304 Not Modified`); ein unveränderter Katalog liefert für jeden Endpunkt eine 304-Antwort und schreibt nichts — bisher als 0 Items gezählt und damit nach 48 h nicht von einem echten Ausfall (leere 200er) unterscheidbar. Jeder SC-Wiki-Schritt (Commodity, Vehicle, Blueprint, Manufacturer, Item) meldet bei einem reinen 304-Lauf jetzt seinen Live-Zeilenbestand statt 0, sodass nur noch ein echter Leerlauf den Alarm auslöst (analog zur `uex_sync`-Korrektur; REQ-OBS-011, #1182).

## [v1.3.3](https://github.com/krt-profit/basetool/releases/tag/v1.3.3) - 2026-07-12

### Added

- **Monitoring: Neuer Alarm `ContainerPidsHigh` warnt, bevor ein Container sein Task-Limit (cgroup-`pids`) erschöpft.** Beim Ingest-Vorfall am 12.07.2026 füllte sich das 2048er-`pids`-Limit durch nicht abgeräumte Healthcheck-Zombie-Prozesse, während die JVM-Thread-Metrik flach blieb — daher schlug `JvmThreadsHigh` nicht an und nur der Absturz danach meldete sich. Ergänzend zur Ursachenbehebung (`init: true`, REQ-OPS-019) überwacht der neue Alarm die cAdvisor-Metrik `container_threads` (>80 % = 1638 der 2048er-Grenze) für die vier JVM-Container backend/frontend/ingest/keycloak und fängt so jede künftige `pids`-Erschöpfung ab; dafür wird die cAdvisor-`process`-Metrikgruppe aktiviert (REQ-OBS-014).

- **Monitoring: JVM-Absturzursachen landen jetzt in Loki.** Native Fehler wie `pthread_create failed` / `unable to create native thread`, die die JVM direkt auf die Container-Ausgabe schreibt (außerhalb von Logback) und die deshalb bisher nie in Loki ankamen, werden jetzt zusätzlich als eigener, shipper-seitig maskierter Stream (`app="<dienst>-stdout"`) versendet — so ist die Ursache eines native-Thread-Absturzes auch nachträglich in Grafana sichtbar. Die zugehörige Regel `JvmNativeThreadExhaustion` ist vorbereitet, bleibt aber bis zur Verifikation auf der Teststrecke deaktiviert (REQ-OBS-007/-014, ADR-0095).

### Fixed

- **Ingest-Gateway (Ein-Klick-Import) stürzt nicht mehr nach ~17 h Laufzeit ab.** Der HTTPS-Healthcheck der JVM-Dienste erzeugte über BusyBox-`wget` alle 30 s einen nicht abgeräumten `ssl_client`-Zombie-Prozess; da die JVM als PID 1 lief und Waisen nicht abräumt, füllte sich der `pids`-Cgroup-Grenzwert (2048) nach rund 17 h und die JVM konnte keine Threads mehr anlegen — Absturz, Neustart-Schleife und Alarm-Mails. Backend, Frontend und Ingest laufen jetzt mit `init: true` (tini als PID 1 räumt die Zombies ab; REQ-OPS-019).

- **Preis-Übersicht wird wieder angezeigt.** Nach der CSP-Härtung (`style-src-attr 'none'`, ADR-0093) blieb die Handelsmatrix unter „Preis-Übersicht" unsichtbar: Die Tabelle wurde per CSS-Klasse ausgeblendet, das Skript blendete sie aber noch über das inzwischen wirkungslose `style.display` ein, und die dynamischen Abstandszeilen des virtuellen Scrollens trugen ein vom Browser blockiertes `style="height:…"`. Sichtbarkeit läuft jetzt über das Umschalten der Klasse, die Zeilenhöhe über das CSSOM — die Seite funktioniert wieder.

- **Monitoring: Prometheus übernimmt geänderte Scrape-/Alert-Konfiguration jetzt zuverlässig.** Weil die Config-Dateien Single-File-Bind-Mounts sind, las der laufende Prometheus nach einem Datei-Update (neuer Inode) weiter die alte Fassung, bis der Container neu erzeugt wurde — ein Reload half nicht, und nach der Portumstellung des Ingest-Actuators (11262→11272) blieb das Ingest-Ziel unüberwacht (kritischer `TargetDown`). `deploy.sh` gleicht die Config jetzt bei jedem Durchlauf gegen die Datei ab und erzeugt den betroffenen Dienst bei Abweichung selbstheilend neu; der neue Alarm `PrometheusConfigStale` meldet eine nie übernommene Konfiguration (REQ-OBS-014, REQ-OPS-013).

- **Weitere per JavaScript aufgebaute Oberflächen funktionieren wieder unter der verschärften CSP.** Nach derselben CSP-Härtung blieben weitere client-seitig erzeugte Elemente unsichtbar oder verloren ihr Layout — u. a. die Statuszeilen der Gewinn-Übersicht, die dynamisch hinzugefügten Auftragspositionen, diverse SCU-Hinweise (Auftrag, Lager, Umbuchen, Ausbuchen), der „Zurückziehen"-Knopf im Anspruchs-Dialog, die Missions-Frequenzanzeige, die Operations-Vorschau und die „Keine Terminals gefunden"-Zeile. Sie steuern Layout und Sichtbarkeit jetzt über CSS-Klassen statt über vom Browser blockierte Inline-Stile.

## [v1.3.2](https://github.com/krt-profit/basetool/releases/tag/v1.3.2) - 2026-07-11

### Fixed

- **Materialbörse: Beim Freigeben eines Materials wird kein zweites Mengenfeld mehr angezeigt.** Der Freigabe-Dialog (aus „Mein Lager" heraus wie über „Material anbieten") blendete unter dem korrekten Feld „Menge anbieten" fälschlich noch das Item-Mengenfeld „Menge (Stück)" ein, das eigentlich nur beim Anbieten craftbarer Items gilt. Ursache war eine CSS-Regel, die das per `hidden` versteckte Feld überstimmte (REQ-MARKET-002/012).

## [v1.3.1](https://github.com/krt-profit/basetool/releases/tag/v1.3.1) - 2026-07-11

### Fixed

- **Betrieb: Deploy-blockierende Subnetz-Kollision im Compose-Stack behoben.** Das mit v1.3.0 neu hinzugekommene Netzwerk `net-redis-backend` (Redis-Fan-out für die Live-Benachrichtigungen) war auf dasselbe Subnetz `172.28.11.0/24` gepinnt wie das bestehende `net-proxy-grafana`. Da Grafana dieses Netz aktiv hält, brach der v1.3.0-Deploy beim Anlegen mit „Pool overlaps with other one on this address space" ab und löste einen Rollback aus. `net-redis-backend` liegt jetzt auf dem freien `172.28.12.0/24`.

## [v1.3.0](https://github.com/krt-profit/basetool/releases/tag/v1.3.0) - 2026-07-11

### Added

- **Die Auftrags-Warteschlange aktualisiert sich jetzt live für alle Betrachter.** Legt jemand einen Auftrag an oder ändert die Priorität per Drag-and-drop, sehen andere Betrachter der Warteschlange die Änderung ohne Neuladen — jeder in seiner eigenen Filter- und Seitenansicht. Auch von Gästen angelegte Aufträge erscheinen sofort (serverseitig ausgelöst, da Gäste keine Live-Verbindung haben). Wer gerade eine Zeile per Drag-and-drop verschiebt, wird dabei nicht gestört (REQ-FE-015, ADR-0094, #1102).

- **Die Auftrags-Detailseite aktualisiert sich jetzt live für alle Betrachter.** Ändert jemand Status, Bearbeiter, Materialien, Übergaben oder eine Eintragung eines Auftrags, sehen andere Betrachter desselben Auftrags die Änderung ohne Neuladen; Statusänderungen, Bearbeitungen und Löschungen aktualisieren zusätzlich die Auftrags-Warteschlange aller Betrachter. Ist gerade ein Dialog offen, erscheint stattdessen eine „Aktualisierungen verfügbar"-Schaltfläche (REQ-FE-015, ADR-0094, #1102).

- **Operationen aktualisieren sich jetzt live für andere Betrachter.** Speichert jemand die Kern-Daten einer Operation (Name, Status, Beschreibung) oder markiert er eine Auszahlung als bezahlt, sehen andere Betrachter derselben Operation die Änderung ohne Neuladen — analog zur Missions-Detailseite. Ändert jemand auf der Missions-Detailseite den Namen, den Status oder die Finanzen einer zur Operation gehörenden Mission, aktualisieren sich auch die eingebettete Einsatz-Tabelle und die Finanzübersicht der Operation live mit. Ist gerade ein Dialog offen, erscheint stattdessen eine „Aktualisierungen verfügbar"-Schaltfläche (REQ-FE-015, ADR-0094, #1115, #1241).

- **Die Kartellbank aktualisiert sich jetzt live für alle Betrachter.** Bucht, bestätigt oder storniert jemand eine Kontobewegung, bearbeitet eine Freigabe, einen Antrag, ein Konto oder die Kontoeinstellungen, sehen andere Betrachter derselben Bank-Ansicht (Konto-Detail, Übersicht, Antrags-Warteschlange, Verwaltung, KRT-Freigaben, Org-Einheits-Bank) die Änderung ohne Neuladen — jeder in seiner eigenen Filter- und Seitenansicht. Ist gerade ein Dialog offen, erscheint stattdessen eine „Aktualisierungen verfügbar"-Schaltfläche (REQ-FE-015, ADR-0094, #1102).

- **„Meine Blueprints": Filter „Nur craftbare anzeigen".** Neben „Raffinerie-Ertrag einrechnen" gibt es jetzt einen Umschalter, der die Blueprint-Liste auf die aktuell craftbaren Blueprints eingrenzt. Der Filter berücksichtigt den Raffinerie-Umschalter (ist er an, zählen auch nur dank Raffinerie craftbare Blueprints als craftbar) und lässt sich mit der Suche kombinieren (REQ-INV-019).

- **Auftraggeber können ihre eigenen Aufträge jetzt einsehen und eingeschränkt bearbeiten.** Mitglieder rein nicht-profit-berechtigter Einheiten, die bisher Aufträge nur anlegen konnten, sehen nun unter „Meine Aufträge" die von ihrer Einheit angeforderten Aufträge und dürfen sie – solange noch nichts geliefert wurde – bearbeiten: Mengen ändern, noch nicht gelieferte Materialien hinzufügen oder entfernen (samt Lösen der zugeordneten Lagerbestände), die Mindestqualität anpassen und den Kommentar bearbeiten. Bearbeiter und Materialübersicht bleiben für sie ausgeblendet. Ändert der Auftraggeber einen Auftrag, werden Offiziere und Leads der bearbeitenden Einheit benachrichtigt (#1186).

- **Monitoring: Abonnements der Live-Aktualisierung auf unbekannte Themen werden jetzt gezählt.** Abonniert ein Client über `/ws/sync` ein Thema, das dieser Server nicht (mehr) kennt — das Anzeichen einer Versions-Diskrepanz zwischen Client und Server —, wird das jetzt über die neue Metrik `basetool_livesync_invalid_topic_total` sichtbar und im Grafana-Board „07" (Panel „Presence relay drops/hour") dargestellt; bisher blieb dieser Fall metrisch unsichtbar (REQ-OBS-011, REQ-FE-015, ADR-0094, #1239).

- **Ingest-Gateway gegen Bot-Angriffe und Scans abgesichert.** Das öffentlich erreichbare Ingest-Gateway wehrt bekannte Scanner-Anfragen (WordPress-, PHP-, Actuator-, WebDAV- und Konfig-Pfade) jetzt vor der Sicherheitsprüfung ab: bekannte Bot-Pfade und nie ausgelieferte Dateiendungen erhalten 404, nicht genutzte HTTP-Methoden (u. a. PUT/DELETE/PATCH/TRACE) 405. Die echten Schnittstellen (`/v1/…`, Health-, Metrics- und OpenAPI-Endpunkte) bleiben unberührt; jede Abweisung wird über `basetool_bot_blocked_total{rule}` sichtbar (REQ-INGEST-009) (#1202).

- **Items auf der Materialbörse anbieten.** Neben „Material anbieten" gibt es jetzt „Item anbieten": craftbare Items (also solche, für die ein Blueprint existiert) lassen sich mit selbst angegebener Menge auf der Börse anbieten. Item-Angebote haben keine Qualität und keinen Standort; ein Mitglied kann dasselbe Item auch mehrfach anbieten (#1185).

- **Materialbörse: Teilmengen anbieten.** Beim Freigeben eines Lager-Postens lässt sich jetzt wählen, wie viel davon angeboten wird — die ganze Menge (Schaltfläche „Alles") oder nur ein Teil. Die angebotene Menge kann später über „Angebot bearbeiten" angepasst werden und darf den aktuellen Lagerbestand nie überschreiten: Wird ein Teil des Postens ausgebucht, sinkt die angezeigte Angebotsmenge automatisch mit; wird der Posten vollständig ausgebucht, wird er gelöscht und das Angebot damit automatisch von der Börse entfernt. Filter und Sortierung „Menge" beziehen sich auf diese effektive Menge (#1183).

### Changed

- **Fehler behoben: Änderungen am Partyleiter und an der Frequenzübersicht einer Mission erscheinen jetzt live bei anderen Betrachtern.** Das Verwaltungs-Panel „Organisation" (Partyleiter + Frequenzübersicht) lag bisher außerhalb der live aktualisierten Bereiche, sodass ein zweiter Betrachter eine Änderung erst nach manuellem Neuladen sah — und seine nächste Bearbeitung unnötig an einem veralteten Stand scheiterte (Konflikt/409). Der Bereich wird nun wie die übrigen Missions-Abschnitte ohne Neuladen mitaktualisiert (REQ-FE-015, ADR-0094, #1120).

- **Suche: Freitext-Suchbegriffe behandeln `%` und `_` jetzt als normale Zeichen.** Bisher wirkten in den Suchfeldern (Materialbörse, Nutzer-, Missions-, Operations-, Schiff-, Blueprint-, Material- und Lager-Suche) eingegebene `%`/`_` als SQL-Platzhalter (`%` = „beliebig viele Zeichen", `_` = „ein beliebiges Zeichen") und verfälschten die Treffer bzw. konnten einen langsamen Scan auslösen. Die Zeichen werden jetzt zentral maskiert und literal gesucht; die Ergebnisse bleiben wie bisher auf den Berechtigungs-Scope begrenzt.

- **Sicherheit: Backend begrenzt jetzt die Größe von JSON-Import-Anfragen (Raffinerie-Screenshot-Import).** Ein übergroßer Nicht-Multipart-JSON-Body auf dem Import-Endpunkt wird nun mit 413 abgewiesen, bevor er in den Speicher geladen wird — bisher band Jackson erst die gesamten Arrays, ehe die `@Size`-Prüfung griff (Speicher-DoS). Der Schutz greift auch bei fehlender `Content-Length` (chunked). Neue Metrik `basetool_request_body_rejected_total` samt Alarm `RequestBodyRejectedSpike`.

- **Sicherheit: JWT-Audience-Prüfung für Backend und Ingest-Gateway aktivierbar gemacht (standardmäßig aus).** Beide Dienste lassen sich jetzt so konfigurieren, dass sie nur noch Tokens mit `aud=basetool-backend` akzeptieren statt jedes gültig signierte Realm-Token; dem Backend fehlte dafür bisher die Verdrahtung. Gesteuert über die Umgebungsvariablen `IRI_BACKEND_EXPECTED_AUDIENCES` bzw. `IRI_INGEST_EXPECTED_AUDIENCES`, standardmäßig leer (aus), damit Entwicklungs- und E2E-Realms unberührt bleiben. In Produktion beide auf `basetool-backend` setzen — erst nachdem geprüft wurde, dass der Realm diesen `aud` in die Tokens schreibt, sonst werden alle Anmeldungen abgewiesen (Audit L-1).

- **Sicherheit: Alle Inline-`style`-Attribute aus den Templates entfernt; die CSP sperrt Inline-Styles jetzt vollständig (`style-src-attr 'none'`).** Bisher erlaubte die Content-Security-Policy Inline-`style=""`-Attribute (`'unsafe-inline'`) — der letzte verbliebene CSP-Kompromiss. Alle rund 640 statischen Inline-Styles wurden in generierte CSS-Klassen (`inline-migration.css`) überführt, bedingte Werte (Modal-Sichtbarkeit, Deckkraft, Farbe) in Klassen-Umschalter, und dynamische Fortschrittsbalken-Breiten in ein per JavaScript gesetztes Attribut; die Modal-/Anzeige-Umschalter laufen nun über `classList` statt über Inline-Styles. Ein eingeschleustes Inline-`style`-Attribut wird damit vom Browser blockiert (ADR-0093).

- **Sicherheit: Präsenz-WebSocket der Missionsdetails gegen Überlastung durch einen einzelnen Nutzer gehärtet.** Der „Wer bearbeitet gerade?"-Kanal einer Mission verarbeitete Fokus-/Heartbeat-Nachrichten bisher ohne Begrenzung — ein einzelner angemeldeter Client konnte mit beliebig vielen frei erfundenen Abschnitts-Schlüsseln den Speicher und die Broadcast-Last für alle Betrachter einer Mission unbegrenzt aufblähen. Fokus-/Heartbeat-Nachrichten werden jetzt wie der bereits abgesicherte „geändert"-Kanal pro Sitzung ratenbegrenzt, überlange Abschnitts-Schlüssel verworfen und die Zahl der je Mission geführten Abschnitte gedeckelt (REQ-FE-010).

- **Sicherheit: Anonymes Anlegen von Item-Aufträgen unterliegt jetzt demselben engen Ratenlimit wie das Anlegen von Material-Aufträgen.** Die Ratenlimit-Regel deckte bisher nur `POST /api/v1/orders` ab; das aufwendigere Item-Auftrags-Anlegen unter `POST /api/v1/orders/items` fiel nur unter das lockere Gesamtlimit. Beide Pfade teilen sich nun das enge Anlege-Limit (REQ-SEC-011).

- **Sicherheit: Das gemeinsame Geheimnis der Discord-Konto-Prüfung muss jetzt mindestens 32 Zeichen lang sein.** Der interne Endpunkt zur Kollisionsprüfung bei der Discord-Erstanmeldung ist absichtlich vom Ratenbegrenzer ausgenommen; sein gemeinsames Geheimnis ist damit die einzige Absicherung. Ein zu kurzes, erratbares Geheimnis lässt den Dienst jetzt beim Start abbrechen, ein leeres deaktiviert den Endpunkt weiterhin (REQ-SEC-022).

- **Sicherheit/Lieferkette: Signierte SBOMs bilden nur noch die ausgelieferte Laufzeit ab; die Gradle-Distribution ist per Prüfsumme fixiert.** Die CycloneDX-SBOMs (Backend, Frontend, Ingest) enthielten bisher auch Build-/Test-Abhängigkeiten und wiesen eine bereits gepatchte PostgreSQL-Version (42.7.11 statt 42.7.12) aus, was Downstream-CVE-Scanner mit Fehlalarmen gegen nie ausgelieferte Bibliotheken versorgte; sie sind jetzt auf den Laufzeit-Klassenpfad eingegrenzt und neu erzeugt. Zusätzlich ist die Gradle-Distribution über `distributionSha256Sum` an die offizielle Prüfsumme gebunden, sodass ein manipuliertes Wrapper-Distributionsarchiv den Build abbricht.

- **Härtung: Live-Aktualisierung gegen Signal-Fluten einzelner Nutzer abgesichert.** Die Zahl gleichzeitig offener Live-Verbindungen (`/ws/sync`) pro Nutzer ist jetzt gedeckelt, und die Rate der Live-Signale je Ansicht (z. B. Auftrags-Warteschlange, Kartellbank) ist insgesamt begrenzt — unabhängig davon, wie viele Verbindungen oder Tabs beteiligt sind. So kann ein fehlerhafter oder böswilliger Client die Live-Aktualisierung anderer nicht mehr durch eine Signal-Flut ausbremsen; normale Mehrfach-Tab-Nutzung bleibt unberührt (REQ-FE-015, ADR-0094, #1243).

- **Fehler behoben: Der vollständige Docker-Entwicklungs-Stack (`docker compose --profile dev up`) startet den Backend-Dienst wieder sauber.** Die Redis-Verbindung für die Live-Benachrichtigungs-Fan-out (ADR-0094) wird jetzt in allen Profilen aus `REDIS_HOST` aufgelöst (der Compose-Anchor setzt sie auf den `redis`-Dienst), statt im Dev-Profil auf `localhost` zu zeigen, und `backend-dev` wartet vor dem Start auf einen gesunden `redis-dev`. Produktion bleibt unverändert (REQ-FE-015, ADR-0094, #1246).

- **Sicherheit: PostgreSQL-JDBC-Treiber auf 42.7.12 angehoben (CVE-2026-54291).** Der von Spring Boot vorgegebene Treiber 42.7.11 konnte einen mit `channelBinding=require` angeforderten SCRAM-Handshake stillschweigend auf die Variante ohne Kanalbindung herabstufen und so den Schutz vor Man-in-the-Middle-Angriffen verlieren; die gepatchte Version 42.7.12 wird jetzt erzwungen. Der wöchentliche OWASP-Abhängigkeits-Scan ist damit wieder grün — die parallel gemeldeten httpcore-4.4.x-Funde (CVE-2026-54399/-54428) waren Fehlalarme, da die CVEs nur die nicht ausgelieferte HttpCore-5.x-Reihe betreffen, und wurden begründet unterdrückt.

- **Navigation: „Blueprint-Verfügbarkeit" liegt jetzt unter „Flotte & Logistik".** Der Menüpunkt wanderte aus der Gruppe „Handel" in die Gruppe „Flotte & Logistik" und steht dort direkt unter „Auftragsverwaltung".

- **Härtung: Metrik- und Health-Endpunkte der öffentlichen Dienste laufen jetzt auf einem eigenen, nur intern erreichbaren Port.** Frontend und Ingest-Gateway liefern `/actuator/**` (Health und Prometheus-Metriken) in Produktion nicht mehr über den öffentlichen App-Port aus, sondern über einen dedizierten Management-Port (Frontend 18091, Ingest 11272), der weder auf dem Host noch über den Reverse-Proxy veröffentlicht wird — erreichbar nur vom Monitoring-Netz (Prometheus) und vom containerinternen Health-Check. Der öffentliche Port beantwortet `/actuator/**` damit auf Anwendungsebene mit 404, unabhängig von der Edge-Sperre — analog zum bereits so betriebenen Keycloak-Management-Port. Backend bleibt unverändert (ohnehin nicht aus dem Internet erreichbar). Nur Produktion betroffen; Entwicklungs-/Test-/E2E-Umgebungen bleiben unverändert (ADR-0090, REQ-OBS-005).

- **Härtung: Auch der Health-Endpunkt der öffentlichen Dienste wird laufend als „von außen gesperrt" überwacht.** Bisher prüfte die Edge-Überwachung nur, dass `/actuator/prometheus` von außen mit 404 abgewiesen wird; die Health-Endpunkte (`/actuator/health`) blieben ungeprüft. Die internen Blackbox-Sonden und der tägliche externe Prüf-Workflow bestätigen jetzt für Frontend und Ingest-Gateway, dass sowohl Metriken als auch Health von außen 404 liefern — driftet die Sperre (etwa nach einem NPM-Umbau), schlägt `EdgeActuatorDenyBroken` an. Metriken erreichen Prometheus weiterhin über das interne Monitoring-Netz, der Health-Check läuft containerintern (REQ-OBS-005/-012). Anonyme Seitenaufrufe (etwa durch Uptime- oder Suchmaschinen-Bots) legten bisher jeweils eine 30 Tage gültige Sitzung in Redis an; so liefen über 16.000 verwaiste Sitzungen auf, die den Speicher langfristig gefüllt und irgendwann Anmeldungen blockiert hätten. Nicht angemeldete Sitzungen laufen jetzt nach kurzer Zeit ab, während das 30-Tage-Fenster „Angemeldet bleiben" erst nach erfolgreicher Anmeldung greift und für Mitglieder unverändert bleibt. Ein neuer Alarm (`ActiveSessionsRunaway`) warnt, falls sich so etwas wiederholt (REQ-SEC-025, ADR-0088).

- **Fehler behoben: Ein als Transfer gekennzeichnetes Ausbuchen ohne Ziel vernichtete stillschweigend Lagerbestand.** Wurde ein Ausbuchvorgang ausdrücklich als Transfer, aber ohne Zielperson und ohne Zielort abgeschickt (nur über einen fehlerhaften Client erreichbar, nicht über die Oberfläche), entfernte das System den Quellbestand und protokollierte ihn fälschlich als „verbraucht", statt die Anfrage abzuweisen. Solche zielosen Transfers werden jetzt mit einer klaren Fehlermeldung (400) zurückgewiesen; es geht kein Bestand verloren und es entsteht kein irreführender Protokolleintrag (REQ-INV-025).

- **Fehler behoben: Nach einem Dialog im Lager (z. B. Umbuchen oder Ausbuchen) klappte die Ansicht wieder komplett zu.** Nach einer Aktion über ein Dialogfenster wurde die Lagertabelle an Ort und Stelle neu aufgebaut und alle zuvor aufgeklappten Material-Gruppen und Standort-Stapel waren wieder eingeklappt. Der Aufklapp-Zustand bleibt jetzt erhalten: aufgeklappte Gruppen und Stapel bleiben offen (und ein wiederhergestellter Stapel lädt seine aktualisierten Einträge neu) — auch nach einem Filterwechsel. Betrifft „Mein Lager" und „Globales Lager" (REQ-INV-002).

- **Fehler behoben: Kein Serverfehler mehr, wenn zwei Logistiker derselben Squadron gleichzeitig die erste Eintragung auf denselben Materialposten eines Spezialkommando-Auftrags vornehmen.** Bisher konnte der Verlierer dieses seltenen Wettlaufs einen internen Serverfehler (500) statt einer gespeicherten Eintragung erhalten. Die Eintragung wird jetzt automatisch in einer frischen Transaktion erneut versucht (der zuletzt Speichernde gewinnt); nur ein tatsächlich anhaltender Konflikt meldet weiterhin sauber „Konflikt" (409) statt eines Serverfehlers.

- **Fehler behoben: Ein Materialposten eines Spezialkommando-Auftrags kann nicht mehr überbucht werden, wenn zwei verschiedene Squadrons gleichzeitig ihre erste Eintragung vornehmen.** Bisher sahen sich zwei Squadrons, die im selben Moment erstmals denselben Posten eintrugen, gegenseitig nicht und trugen beide ihre Menge ein, sodass die Summe die benötigte Menge überschreiten konnte. Die Eintragungen eines Auftrags werden jetzt kurz serialisiert (Sperre auf dem Auftrag), sodass die zweite Squadron den bereits eingetragenen Anteil sieht und bei Überbuchung sauber mit einer Fehlermeldung abgewiesen wird (REQ-ORDERS-024, ADR-0092).

- **Fehler behoben: Materialbörse zeigt jetzt bei jedem Anbieter dessen Einheiten-Abzeichen an.** Bisher blieb das Abzeichen leer, wenn der angebotene Posten keiner Einheit zugeordnet war (etwa persönlicher oder Alt-Bestand) — obwohl der Anbieter Mitglied einer Staffel ist. Das Abzeichen richtet sich jetzt nach den Mitgliedschaften des Anbieters selbst: Alle Staffeln, Spezialkommandos und Bereiche, denen er angehört, erscheinen hinter dem Namen (Staffeln zuerst, dann Spezialkommandos, dann Bereiche, je Gruppe alphabetisch) — ein „primäres" wird nicht mehr ausgewählt (REQ-MARKET-001).

- **Verbesserung: Nutzer-Auswahlfelder suchen jetzt serverseitig statt die ganze Mitgliederliste vorzuladen.** Die Auswahlfelder, die bisher alle Mitglieder komplett vorluden (u. a. in Lager, Raffinerie, Aufträgen, Missionen, Rollen-/Leitungsverwaltung und der Kartellbank), durchsuchen die Mitglieder jetzt erst bei der Eingabe über den Server — schneller und skalierbar für tausende Konten. Gäste-Felder und die Halter-Auswahl bleiben unverändert (#1193).

- **Verbesserung: Auch die Einzahler-/Empfänger-Auswahl im Kontobewegungs-Dialog sucht jetzt serverseitig.** Das Gegenpartei-Auswahlfeld der Ein-/Auszahlung lud als letztes Bank-Auswahlfeld noch die gesamte Nutzerliste vor; es durchsucht die Mitglieder jetzt erst bei der Eingabe über den Server. Der „Kein Tool-Account"-Umschalter und die abhängige Einheiten-Auswahl funktionieren unverändert (Folgeänderung zu #1193).

- **Fehler behoben: Serverseitige Nutzer-Auswahlfelder zeigten beim bloßen Aufklappen „Keine passenden Nutzer".** Wurde ein solches Feld ohne Eingabe geöffnet, blieb die Liste leer, weil die leere Suchanfrage am Server als Fehler (HTTP 500) abgewiesen wurde. Ein leeres Suchfeld liefert jetzt die (nach Berechtigung eingegrenzte) Mitgliederliste, sodass sich ein Nutzer wieder direkt auswählen lässt, ohne erst zu tippen (Folgeänderung zu #1193).

- **Weniger Log-Rauschen, wenn das Backend kurz nicht erreichbar ist (z. B. bei einem Neustart/Deploy).** Ein durch den offenen Circuit-Breaker kurzgeschlossener Aufruf wurde bisher dreifach als `WARN` protokolliert und flutete bei jedem Backend-Neustart das Log, sodass ein erwarteter, sich selbst behebender Aussetzer kaum von einem echten Vorfall zu unterscheiden war. Solche kurzgeschlossenen Aufrufe werden jetzt auf `DEBUG` gestuft; das einmalige Öffnen des Breakers, die Metrik und der Alert bleiben als Signal erhalten (#1203).

- **Fehler behoben: Ein langsamer, aber erfolgreicher Backend-Aufruf wird nicht mehr als WARN protokolliert.** Erfolgreiche Aufrufe (z. B. `POST /api/v1/users/sync` mit Status 200), die nur die Langsam-Schwelle überschritten, erschienen im Log fälschlich als WARN. Sie werden jetzt als INFO mit dem Marker „Slow backend call" geführt; WARN bleibt echten Serverfehlern (5xx) und Netzwerkfehlern vorbehalten. Die Latenzüberwachung erfolgt weiterhin über das `http.client.requests`-p95-Histogramm (#1204).

- **Fehler behoben: Hibernate-Warnungen aus dem Backend-Log entfernt.** Die überflüssige explizite `hibernate.dialect`-Angabe (PostgreSQL wird automatisch erkannt) wurde entfernt und die veralteten `@Valid`-Container-Annotationen auf die Typargument-Form (`List<@Valid X>`) umgestellt. Die Validierung bleibt unverändert; das Backend-Log startet nun ohne diese Warnungen (#1206).

- **Fehler behoben: Keine wiederkehrenden Warnungen mehr im Log nach der UEX-Synchronisation.** UEX vergibt mehreren Item-IDs (Basis-Item und seine Skins) dieselbe Spiel-UUID; da `external_uuid` eindeutig ist, kann nur eine Zeile die UUID führen. Das ist erwartetes, dauerhaftes Verhalten, wurde aber bisher bei jedem Lauf pro betroffener Zeile als Warnung geloggt. Diese Fälle werden jetzt auf DEBUG geloggt; die Laufzusammenfassung nennt stattdessen die Gesamtzahl (`sharedUuidDeclined`) (#1205).

- **Fehler behoben: Keine `aurora_version()`-Fehlermeldungen mehr im Keycloak-Datenbanklog.** Die in Keycloak 26.7 neu standardmäßig aktive „Asynchronous-Commit"-Optimierung führte beim Start `SELECT aurora_version()` zur AWS-Aurora-Erkennung aus; auf unserer normalen PostgreSQL-Datenbank existiert diese Funktion nicht, sodass bei jedem Keycloak-Start ein (harmloser, von Keycloak abgefangener) `ERROR: function aurora_version() does not exist` das db-keycloak-Log verschmutzte. Die Optimierung ist nun per `KC_SPI_CONNECTIONS_JPA_QUARKUS_ASYNC_COMMIT=false` abgeschaltet — Verhalten wie vor dem 26.7-Update, die Probe wird an der Quelle vermieden (#1207).

- **Fehler behoben: Wiederkehrende WARN-Meldung im Frontend-Log entfernt.** Spring Data warnte bei jedem Seitenaufruf, ein `@ModelAttribute`-Parameter des Staffel-Kontexts sei „nicht mit @ProjectedPayload annotiert" — ein Fehlalarm, da es sich um normale Listen und nicht um Projektionen handelt. Die im Frontend ohnehin ungenutzte Spring-Data-Web-Unterstützung wird jetzt nicht mehr geladen, wodurch die Fehlmeldung an der Wurzel entfällt (statt nur den Logger stummzuschalten) (#1202).

- **Fehler behoben: Die RED-Panels im Tracing-Dashboard zeigten „No data" mit Fehlerhinweis.** Die drei Panels (Request-Rate, p95-Latenz, Fehlerrate) liefen als TraceQL-Metrics-Abfragen gegen Tempo, was den nicht aktivierten `local-blocks`-Prozessor voraussetzt. Sie beziehen ihre Werte jetzt — nach Route gruppiert — aus den vorhandenen `http_server_requests`-Histogrammen (Prometheus), denselben Serien wie die Latenz-/5xx-Alarme. Kein zusätzlicher Speicher-, Tempo-Neustart- oder Reihen-Aufwand; die Trace-Tabellen „Slow traces"/„Error traces" bleiben unverändert an Tempo (ADR-0076).

- **Weniger fälschliche ERROR-Logs im Frontend bei Backend-Aussetzern.** Zahlreiche Seiten- und Schreib-Handler protokollierten einen bereits an der Backend-Grenze einmalig geloggten Fehler (erwartete 4xx wie Validierung/Konflikt oder einen kurzgeschlossenen Circuit-Breaker) ein zweites Mal als ERROR und konnten so bei einem Backend-Neustart schon beim Öffnen der Startseite oder einer Admin-Seite den `LogbackErrorSpike`-Alarm fälschlich auslösen; solche Fälle werden jetzt einheitlich als DEBUG (Grenze hat bereits geloggt) bzw. WARN geführt, während unerwartete Fehler weiter als ERROR sichtbar bleiben und genuine 500er in AJAX-Handlern nun korrekt als ERROR statt DEBUG erscheinen. Zusätzlich wird der Keycloak-Callsign-Handle nicht mehr ins Log geschrieben und ein abgebrochener Benachrichtigungs-Stream erzeugt keine überflüssige ERROR-Zeile mehr (REQ-OBS-001/-004) (#1227).

- **Weniger Log-Rauschen und bessere Diagnose im Backend.** Abgefangene Fehlschläge der externen Kataloge (UEX, SC-Wiki) werden von ERROR auf WARN gestuft — ein Ausfall dieser instabilen Community-APIs löste über die Endpunkt-/UUID-Fächerung sonst fälschlich den `LogbackErrorSpike`-Alarm aus, obwohl der `external_fetch_errors`-Zähler das eigentliche Signal ist; leere UEX-Antworten werden nun einheitlich als WARN sichtbar statt still verworfen, der 429-Ratenbegrenzer und doppelte WARN-Zeilen für erwartete 400er fluten das Log nicht mehr, fehlgeschlagene P4K-Importe behalten ihren Stacktrace, und Änderungen am öffentlichen Ankündigungs-Banner werden protokolliert. Zudem gelangen ein Gäste-Name und der rohe abgelehnte Feldwert eines JSON-Parsefehlers nicht mehr ins Log (REQ-OBS-001/-004/-013) (#1228).

- **Neue Frühwarn-Metriken für schwer erkennbare Ausfälle.** Drei bisher nur im Log (oder gar nicht) sichtbare Fehlerzustände liefern jetzt ein beschränkt gelabeltes `basetool_*`-Signal mit passendem Alarm: ein fehlgeschlagener Keycloak-Roster-Abruf (`basetool_keycloak_sync_fetch_failures_total` → `KeycloakSyncFetchFailing`), der sonst als erfolgreicher Sync mit 0 Nutzern erschien und lokale Rollen driften ließ; ein dauerhaft fehlschlagender einzelner SC-Wiki-Sync-Schritt (`basetool_scheduled_job_step_failures_total{step}` → `ScWikiStepFailing`), den der Gesamtjob als Erfolg verbuchte; und die REQ-SEC-017-Sperre wartender Konten, die als 403 auf Filter-Ebene an der Fehler-Metrik vorbeilief (`basetool_http_error_total{code=PENDING_APPROVAL}` → `PendingApprovalBlockSpike`). Zusätzlich meldet der Prometheus-Scrape-Endpunkt beim Start, ob er per Basic-Auth abgesichert ist oder mangels Zugangsdaten auf Deny-All steht (REQ-OBS-011) (#1230).

- **Ingest-Gateway: bessere Nachvollziehbarkeit und ein bisher blinder DoS-Schutz.** Das Gateway schreibt jetzt pro `/v1`-Anfrage eine Zugriffs-Logzeile (Methode, Pfad, Status, Dauer) — bisher blieb ein erfolgreicher, abgewiesener (413/429) oder fehlgeschlagener Handoff ohne korrelierte Logspur. Ein durch den offenen Circuit-Breaker kurzgeschlossener Backend-Aufruf wird nun als DEBUG statt WARN geführt (bei einem Backend-Neustart floss sonst pro Aufruf eine WARN-Zeile), während der einmalige Breaker-Zustandswechsel als WARN protokolliert wird. Zudem liefert die 413-Abweisung zu großer Payloads jetzt eine Metrik (`basetool_ingest_payload_rejected_total`) samt Alarm (`IngestPayloadRejectedSpike`), sodass ein Flut- oder Scan-Angriff auf die einzige öffentlich erreichbare Fläche erkennbar wird (REQ-OBS-001/-011) (#1232).

- **Fehler behoben: Ein vorübergehender Keycloak-Fehler während der täglichen Nutzer-Synchronisation stuft keine Konten mehr falsch ein.** Schlug beim rollenindizierten Sync das Auslesen der Mitglieder einer Rolle vorübergehend fehl (Timeout/5xx), wurde diese Rolle bisher stillschweigend allen Inhabern entzogen — ein neu angelegtes Admin-Konto konnte so als „ausstehend" in der Freigabe-Warteschlange landen und bestehende Admins wurden kurzzeitig herabgestuft. Ein solcher Fehler überspringt jetzt den ganzen Lauf (kein degradiertes Schreiben), und die Rollenzuordnung ist gegen Groß-/Kleinschreibungs-Unterschiede zwischen lokalem Katalog und Keycloak abgesichert (REQ-SEC-018).

## [v1.2.6](https://github.com/krt-profit/basetool/releases/tag/v1.2.6) - 2026-07-09

### Added

- **Benachrichtigung, wenn jemand Interesse an einem Materialbörse-Angebot anmeldet.** Anbieter erhalten jetzt eine In-App-Benachrichtigung, sobald ein Mitglied Interesse an einem ihrer Angebote anmeldet, und müssen die Börse dafür nicht mehr selbst im Blick behalten (#1187).

### Changed

- **Fehler behoben: Weiße Rahmen und helle Überlagerung in der Materialbörse-Liste entfernt.** Die Angebotsliste rendert ihre Einträge als Schaltflächen; ohne zurückgesetzte Browser-Standardoptik zeigten nicht ausgewählte Einträge eine helle Überlagerung und weiße Rahmen. Die Liste folgt jetzt wieder dem KRT-Designsystem (#1184).

- **Fehler behoben: Die Materialbörse zeigt jetzt die richtige Mengeneinheit.** Angebote stückbasierter Materialien (Einheit „Stück") wurden auf der Börse, im Detailbereich und im Freigabe-Dialog fälschlich immer als „SCU" angezeigt. Die Menge folgt jetzt der Einheit des Materials (SCU oder Stück) – wie im Lager (#1182).

## [v1.2.5](https://github.com/krt-profit/basetool/releases/tag/v1.2.5) - 2026-07-09

### Changed

- **Keycloak auf 26.7.0 angehoben (Sicherheits- und Wartungsupdate).** Der Anmeldedienst (Keycloak) wird von 26.6.4 auf das Minor-Release 26.7.0 aktualisiert — mit mehreren Sicherheitskorrekturen (u. a. HTTP-Parameter-Pollution im OIDC-Endpunkt, Umgehung der CIBA-Brute-Force-Sperre, TOCTOU beim Umbenennen von Admin-Rollen und eine Rechte-Eskalation in der alten feingranularen Rechteverwaltung). Keine Funktions- oder Konfigurationsänderung: Anmeldung, Realm-Import und der `keycloak-spi`-Provider (Discord-Föderation + Mitgliedschafts-Gate) laufen unverändert weiter. Das gepinnte Container-Image (`quay.io/keycloak/keycloak:26.7`-Digest, weiterhin JDK 21) und die SPI-Artefakte des `keycloak-spi`-Moduls ziehen mit. **Deploy-Hinweis:** Beim nächsten Deploy lädt Compose das neue Image-Digest; der Keycloak-Container muss dafür neu gestartet werden.

- **Verbesserung: Die periodische Keycloak-Synchronisation läuft jetzt einmal täglich um 05:00 (statt stündlich) und belastet Keycloak deutlich weniger.** Sie fragt Rollen jetzt rollen-indiziert statt einzeln pro Nutzer ab und liest Discord-Verknüpfungen nur noch für Konten ohne bestehende Verknüpfung — bei 5000 Konten sinkt die Zahl der Keycloak-Aufrufe pro Lauf von rund 10.000 auf wenige Dutzend. Für eine sofortige Aktualisierung dient der „Jetzt synchronisieren"-Knopf; einstellbar über `APP_KEYCLOAK_SYNC_CRON` / `APP_KEYCLOAK_SYNC_ZONE` (Standard `0 0 5 * * *` / `Europe/Berlin`, ersetzt `APP_KEYCLOAK_SYNC_INTERVAL`).

- **Kapazität: Das Tool ist jetzt auf 5000 Nutzerkonten und 200 gleichzeitige Nutzer ausgelegt.** Keycloak, seine Datenbank, die Backend-Datenbank und Redis wurden auf dem 16-GB-Host entsprechend hochdimensioniert; das 30-tägige Anmeldefenster bleibt erhalten (ADR-0085).

## [v1.2.4](https://github.com/krt-profit/basetool/releases/tag/v1.2.4) - 2026-07-09

### Added

- **Neuer Knopf „Jetzt synchronisieren" in der Mitgliederverwaltung.** Administratoren können die Keycloak-Synchronisation manuell anstoßen, statt bis zu eine Stunde auf den nächsten automatischen Lauf zu warten; nach Abschluss erscheint eine Bestätigung und die Mitgliederliste wird direkt mit den aktualisierten Daten neu geladen.

### Changed

- **Verbesserung: Deutlich seltener „Sitzung abgelaufen (gleichzeitige Anmeldungen)".** Die Obergrenze gleichzeitiger Anmeldungen pro Nutzer wurde von 2 auf 10 angehoben. In Kombination mit dem 30-tägigen Sitzungsfenster verdrängte die niedrige Grenze bisher echte Sitzungen von Nutzern, die sich von mehreren Geräten/Browsern anmelden, und zeigte ihnen die Meldung „This session has been expired". Die neueste Anmeldung gewinnt weiterhin, und niemand wird ausgesperrt.

- **Verbesserung: Ein vorübergehender Laufzeitfehler eines Dienstes löst keinen stundenlangen Neustart-/Rollback-Sturm mehr aus.** In der Nacht zum 2026-07-09 erschöpfte das Backend seine Thread-Obergrenze, wodurch sein Health-Check kippte und die automatische Bereitstellung das unveränderte Release wiederholt (fälschlich) „zurückrollte". Der Deploy-Automatismus unterscheidet jetzt einen echten Fehl-Release von einem reinen Laufzeitproblem am bereits laufenden Release und startet in letzterem Fall nur den betroffenen Dienst gezielt neu, statt die Version zurückzurollen. Ein kurzer Keycloak-Ausfall nimmt zudem die App-Container nicht mehr aus dem Health-Zustand (ADR-0083/0084).

- **Verbesserung: Die periodische Keycloak-Synchronisation läuft jetzt stündlich statt jede Minute** und belastet Keycloak dadurch deutlich weniger. Die Frequenz ist über die Umgebungsvariable `APP_KEYCLOAK_SYNC_INTERVAL` (Standard `PT1H`) einstellbar.

- **Monitoring: Frühwarnung bei Thread-Erschöpfung.** Ein neuer Alert (`JvmThreadsHigh`) warnt, sobald ein Dienst sich seiner Thread-Obergrenze nähert — die Ursache des Vorfalls vom 2026-07-09; ein nicht behebbarer Laufzeitfehler am laufenden Release meldet sich jetzt eindeutig (`DeployHealthRestartFailing`) statt als irreführendes Deploy-Rollback.

- **Verbesserung: Die bearbeitende Einheit wird jetzt in der Auftragsübersicht angezeigt.** In der Auftragsverwaltung erscheint die aktuell zuständige Einheit (Staffel oder Spezialkommando) direkt unter der Auftrags-ID und -art — bisher war sie nur in der Auftragsdetailansicht sichtbar (#1188).

## [v1.2.3](https://github.com/krt-profit/basetool/releases/tag/v1.2.3) - 2026-07-08

## [v1.2.2](https://github.com/krt-profit/basetool/releases/tag/v1.2.2) - 2026-07-08

### Changed

- **Fehler behoben: In der Materialbörse ließen sich nicht alle Materialien anbieten.** Die Auswahlliste im Dialog „Material anbieten" zeigte nur die ersten 50 Lager-Posten (alphabetisch) und filterte danach im Browser, sodass Materialien weiter hinten im Alphabet (etwa Savrilium) nicht gefunden und nicht angeboten werden konnten — ohne Fehlermeldung. Die Materialsuche im Dialog läuft jetzt serverseitig, sodass jeder eigene Lager-Posten über die Suche gefunden und angeboten werden kann (REQ-MARKET-002).

## [v1.2.1](https://github.com/krt-profit/basetool/releases/tag/v1.2.1) - 2026-07-08

### Changed

- **Fehler behoben: Der Freigabe-Dialog der Materialbörse öffnete sich unsichtbar.** Sowohl der Knopf „Material anbieten" als auch die Lager-Checkbox „Für Börse freigeben" öffneten den Dialog, der jedoch unsichtbar blieb — es ließ sich kein Material einstellen, und ein späteres Abwählen der Checkbox schlug mit „Kein aktives Angebot" fehl. Der Dialog wird jetzt korrekt angezeigt (#1174).

## [v1.2.0](https://github.com/krt-profit/basetool/releases/tag/v1.2.0) - 2026-07-07

### Added

- **Neue Materialbörse (Flotte & Logistik).** Ein zentraler, für alle Mitglieder sichtbarer Marktplatz: Spieler geben Überschüsse aus ihrem Lager frei (Checkbox „Für Börse freigeben" mit Markdown-Bemerkung, max. 20.000 Zeichen) und andere melden Interesse an. Angezeigt wird nur, wer welches Material in welcher Qualität und Menge anbietet — Standort und Übergabeort bleiben privat, und Interessenten-Namen sieht ausschließlich der Anbieter. Verhandlung und Übergabe laufen direkt zwischen den Spielern (REQ-MARKET-001…010, ADR-0082).

- **Materialbörse im Audit-Log.** Freigabe, Deaktivierung, Bemerkungs-Bearbeitung sowie An- und Abmelden von Interesse werden protokolliert; das Admin-Audit-Log erhält einen eigenen „Materialbörse"-Tab.

### Changed

- **Verbesserung: Die Operationsseite lädt schneller und belastet Datenbank und Verbindungspool unter hoher Gleichzeitigkeit deutlich weniger.** Die Finanzübersicht einer Operation ermittelt ihre Summen jetzt über schlanke Datenbank-Aggregate, statt sämtliche Finanz- und Raffinerieeinträge aller Einsätze zu laden; die Aufschlüsselung je Einsatz wird erst beim Aufklappen nachgeladen, die vier Detail-Abfragen laufen parallel, und der „Bezahlt"-Schalter aktualisiert nur noch die betroffene Zeile, statt die gesamte Auszahlung neu zu berechnen. Die Operationsauswahl auf der Einsatzseite wird zudem nur noch beim vollständigen Seitenaufbau geladen und auf aktuelle Operationen begrenzt (#1109).

- **Verbesserung: Live-Aktualisierungen auf der Missionsseite bleiben stabil, auch wenn einzelne Betrachter eine hängende Verbindung haben.** Ein „eingefrorener" Betrachter (etwa ein zugeklapptes Notebook mit noch offener Verbindung) blockiert nicht mehr die Echtzeit-Aktualisierungen aller übrigen Betrachter; zudem werden gleichzeitig verbindende und trennende Betrachter jetzt zuverlässig erfasst, sodass niemand mehr still eine veraltete Ansicht behält (#1109).

- **Fehler behoben: Der Benachrichtigungs-Zähler konnte unmittelbar nach einer Aktion kurz einen veralteten Stand anzeigen.** Die Echtzeit-Benachrichtigung wird jetzt erst nach dem endgültigen Speichern ausgelöst, sodass die anschließende Aktualisierung den korrekten ungelesenen Stand liest (#1109).

- **Fehler behoben: Beim Bearbeiten einer Operation ohne JavaScript zeigte ein Bearbeitungskonflikt eine irreführende allgemeine Fehlermeldung.** Ein Konflikt (gleichzeitige Bearbeitung durch jemand anderen) meldet jetzt korrekt, dass neu geladen werden muss, statt einen unklaren Fehler anzuzeigen (#1109).

- **Verbesserung: Die Oberfläche ist unter hoher Last stabiler.** Sehr große Katalog-Abfragen (etwa die Materialübersicht) werden bei gleichzeitigem Zugriff nur noch einmal geladen statt mehrfach parallel, und die Zahl gleichzeitiger Echtzeit-Verbindungen pro Nutzer (viele offene Tabs) wird begrenzt (#1109). Gleichzeitige Live-Aktualisierungen und Teilansichten belasten den Server zudem etwas weniger, und die Auftrags-Detailseite lädt die Nutzerliste nur noch beim vollständigen Seitenaufbau (#1109).

## Archiv

Ältere Versionen (vor v1.2.0, d. h. v1.1.11 und älter) sind in [CHANGELOG-ARCHIVE.md](CHANGELOG-ARCHIVE.md) archiviert.
