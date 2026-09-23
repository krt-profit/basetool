# UC-33 — Einsatz: In-Place-Bearbeitung, Crew-Board & Live-Sync

|                |        |
|----------------|--------|
| **ID**         | UC-33  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`MissionCoreEditInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionCoreEditInPlaceE2eTest.java) · [`MissionUnitResponsibleClearE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionUnitResponsibleClearE2eTest.java) · [`MissionParticipantCountE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionParticipantCountE2eTest.java) · [`MissionListFilterInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionListFilterInPlaceE2eTest.java) · [`MissionLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionLiveSyncE2eTest.java) · [`MissionOrganisationLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionOrganisationLiveSyncE2eTest.java) · [`MissionCrewBoardTouchDragE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionCrewBoardTouchDragE2eTest.java) · [`MissionOwnerChangeE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MissionOwnerChangeE2eTest.java) |
| **Basis-Flow** | [UC-02](UC-02-mission-anlegen.md) · Spec [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (REQ-FE-001/-005/-010/-011/-015) |

## Akteur

`test-admin` (ADMIN, IRIDIUM-Mitglied) — bearbeitet über die Rollenhierarchie jeden Einsatz. Die Live-Sync-Tests öffnen denselben Einsatz in **zwei Browser-Kontexten** desselben Nutzers: zwei getrennte WebSocket-Sitzungen sind genau das, was der Relay auffächert; ein echter zweiter Nutzer träfe denselben Handler-Zweig.

## Vorbedingungen

Per REST geseedet: IRIDIUM-Mitgliedschaft (Einsätze sind staffel-gescopt) und je Klasse eigene Einsätze — ohne Teilnehmer für die Zähler-Tests, mit dem handelnden Nutzer als registriertem Teilnehmer und je einer Einheit pro Pfad für den Verantwortlichen-Test, zwei unterscheidbar benannte für den Listenfilter. Gäste und Party-Leads tragen einen Namen, den kein Realm-Nutzer hat, damit sie auf dem Gast-Pfad bleiben.

## Auslöser

Der Nutzer bearbeitet einen Einsatz, filtert die Einsatzliste oder verschiebt auf einem Touch-Gerät einen Teilnehmer; ein zweiter Betrachter hat denselben Einsatz offen.

## Hauptablauf

### Kerndaten in place speichern (`MissionCoreEditInPlaceE2eTest`, #589)

1. Im Tab Verwaltung den Namen ändern und speichern, dann ohne Reload ein zweites Mal speichern.
2. Einen Kalender-Link ohne `https` eintragen — der Browser akzeptiert ihn (`type=url`), das Backend-`@Pattern` nicht — und speichern; danach mit gültigem Link erneut speichern.

### Verantwortlichen einer Einheit entfernen (`MissionUnitResponsibleClearE2eTest`)

3. Im Einheiten-Modal den Verantwortlichen über die auswählbare „Leeren"-Zeile der Combobox entfernen und speichern.
4. An einer zweiten Einheit das Textfeld leeren, den Fokus wegnehmen und speichern.

### Teilnehmerzähler (`MissionParticipantCountE2eTest`, #574)

5. Einen Einsatz ohne Teilnehmer öffnen (Kopfzeile „Teilnehmer 0", Crew-Tab-Badge `0/0`), Reload-Marker setzen und über das Modal einen Gast hinzufügen.

### Listenfilter (`MissionListFilterInPlaceE2eTest`, #573)

6. `/missions?showPast=true` öffnen, Reload-Marker setzen und ein unterscheidendes Token in die Suche tippen.

### Live-Sync zwischen zwei Betrachtern (`MissionLiveSyncE2eTest`, `MissionOrganisationLiveSyncE2eTest`)

7. Kontext A fügt einen Gast-Teilnehmer hinzu; Kontext B schaut nur zu und lädt nie neu.
8. Kontext A legt im Tab Verwaltung ein Ziel an; Kontext B hat den Ziele-Editor im Hintergrund offen.
9. Kontext A setzt im Tab Verwaltung den Party-Lead auf einen Gast; Kontext B steht auf dem Standard-Tab. Vor der Mutation wartet der Test, bis `window.krtLiveSync.subscribedTopics()` das Thema `mission:{id}` enthält.

### Touch-Drag auf dem Crew-Board (`MissionCrewBoardTouchDragE2eTest`, #1936, REQ-MISSION-005, [ADR-0191](../adr/0191-touch-drags-the-crew-board-through-pointer-events.md))

10. In einem Kontext mit Touch (`hasTouch`) die Zeile eines Teilnehmers im Pool „Ohne Einheit" gedrückt halten und auf die Drop-Zone einer Einheit ziehen — getrieben mit synthetischen `PointerEvent`s.
11. Auf einer Personenzeile die berechneten Styles und das `contextmenu`-Ereignis des Drucks prüfen, mit dem der Drag beginnt.

### Besitzerwechsel mit Versionszähler (`MissionOwnerChangeE2eTest`, #1994, BE-SIMP-03)

12. Im Tab Verwaltung den Besitzer über die Server-Such-Combobox auf `test-member` setzen und den Bestätigungsdialog annehmen (`PUT /missions/{id}/owner/ajax`); `#owner-row` trägt vorher `data-ownership-version="0"`.
13. Den Zähler auf der Zeile auf `0` zurücksetzen — die Seite eines zweiten Verwalters, die vor dem Wechsel geöffnet wurde — und den Besitzer auf `test-officer` setzen.

## Erwartetes Ergebnis

- **Kerndaten:** Der erste Speichervorgang wird ohne Reload persistiert (per API gelesen), der zweite läuft ohne 409 durch — der AJAX-Zwilling hat die vier Versionszähler des Formulars zurückgeschrieben. Der ungültige Link zeigt den Feldfehler inline ohne Navigation; er verschwindet nach dem gültigen Speichern.
- **Verantwortlicher:** Beide Einheiten haben danach keinen Verantwortlichen mehr (per API); beim Leeren springt das Textfeld beim Fokusverlust **nicht** auf den entfernten Namen zurück.
- **Zähler:** Kopfzeile und Crew-Tab-Badge zeigen **in place** 1 und `0/1`; der Marker überlebt.
- **Filter:** Die passende Zeile bleibt, die andere verschwindet, ohne Reload; die URL trägt den Parameter `search`.
- **Live-Sync:** B zeigt den neuen Teilnehmer im Kopfzähler, die neue Zielzeile im Editor bzw. den neuen Party-Lead in der Übersicht — jeweils **in place**, nur durch das Änderungssignal.
- **Touch:** Der Teilnehmer ist danach an Bord der Einheit und nicht mehr im Pool — der Pointer-Drag erreicht denselben Crew-Endpunkt wie der Maus-Drop. Die Zeile hat `user-select: none`, eine `touch-action`, die vertikales Scrollen und Zoomen dem Browser lässt, und das Board bricht das `contextmenu` des Drucks ab.
- **Besitzerwechsel:** Der erste Wechsel antwortet 200, das Backend führt `test-member` als Besitzer, und `#owner-row` trägt **in place** den Zähler `1` (Marker überlebt). Der Wechsel mit dem veralteten Zähler `0` wird mit **409** abgewiesen, und der Besitzer bleibt `test-member`.

## Sonderfälle & Lehren

- **Zähler außerhalb des Fragments.** Das Hinzufügen rendert nur `#crew-board-results` neu, die Zähler stehen außerhalb. Der Fix trägt die frischen Zahlen im Fragment (`#crew-count-meta`), und ein `krt:swapped`-Listener patcht `#facts-registered`, `#facts-checked-in` und `#tab-crew .tab-count` — die Verallgemeinerung des Finanz-Badge-Vorbilds.
- **Zwei Wege zurück zu „keiner"** (REQ-FE-011, ADR-0053): Die Combobox verschluckte die leere Option in den Platzhalter und stellte beim Fokusverlust den gerade entfernten Namen wieder her. Der Test deckt beide Wege je in einer Methode ab, auf verschiedenen Einheiten (per `data-name`), damit die Reihenfolge egal ist.
- **Live-Sync-Mechanik** (REQ-FE-010, ADR-0031, REQ-FE-015, ADR-0094): Der Relay sendet `{"type":"changed","sections":[…]}` an jeden anderen Socket des Einsatzes und schließt die auslösende *Sitzung* aus, nicht den Nutzer. Die Einsatz-Detailseite nutzt den gemeinsamen `/ws/sync`-Socket über das `missionPresence`-Abo `mission:{id}`; der alte Pro-Einsatz-Socket ist mit #1236 entfallen. Der Abschnittsschlüssel `objectives` fehlte einmal sowohl in der Relay-Whitelist als auch im Empfänger — die Ziele anderer Betrachter blieben bis zum Reload veraltet.
- **Was der Touch-Test nicht beweisen kann:** Synthetische `PointerEvent`s treiben die Zustandsmaschine des Boards von Halten bis Schreibzugriff. Ob Android sein Kontextmenü tatsächlich unterdrückt und die Seite unter dem Finger nicht scrollt, bleibt ein Test mit einem echten Telefon. `isMobile` wird bewusst nicht gesetzt — Firefox lehnt es ab.
- **Die Besitzer-Kandidaten brauchen keine Staffel** (korrigiert 2026-09-23). Die Klasse kam mit #1994 und rief `BackendSeeder.ensureIridiumMembership` auch für `test-member` und `test-officer` auf. Diese Methode ordnet den *angemeldeten* Nutzer selbst zu, über den ADMIN-only-Endpunkt `PATCH /api/v1/users/{id}/memberships` — für einen Nicht-Admin ohne Staffel ist das ein 403, und die Klasse endete in jeder Matrixzelle mit `initializationError`, bevor der Flow lief. Ein Besitzerwechsel nimmt jeden existierenden Nutzer an, und die Suche eines nicht gepinnten Admins sieht alle Nutzer; deshalb legt der Seeder die beiden Kandidaten nur noch per `getUserId` (eine Anmeldung) als `app_user` an. Einen Nicht-Admin einer Staffel zuzuordnen geht nur über `assignStaffelMembership` mit Admin-Zugangsdaten.
