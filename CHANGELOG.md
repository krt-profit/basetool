# Changelog

## [Unreleased]

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
