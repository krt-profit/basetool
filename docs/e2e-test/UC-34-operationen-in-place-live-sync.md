# UC-34 — Operationen: In-Place-Schreibvorgänge & Live-Sync

|                |        |
|----------------|--------|
| **ID**         | UC-34  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`OperationWritesInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OperationWritesInPlaceE2eTest.java) · [`OperationLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OperationLiveSyncE2eTest.java) · [`OperationMissionCrossPublishLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OperationMissionCrossPublishLiveSyncE2eTest.java) |
| **Spec**       | [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (REQ-FE-001/-015) · [ADR-0094](../adr/0094-tool-wide-topic-room-live-sync-relay.md) |

## Akteur

`test-admin` (ADMIN) — legt über die Rollenhierarchie jede Operation an, bearbeitet und löscht sie. Die Live-Sync-Tests nutzen zwei Browser-Kontexte desselben Nutzers, also zwei getrennte `/ws/sync`-Sockets.

## Vorbedingungen

Per REST geseedet: IRIDIUM-Mitgliedschaft sowie je Test eine eigene Operation; für den Cross-Publish-Fall zusätzlich ein Einsatz, der dieser Operation zugeordnet ist.

## Auslöser

Der Nutzer legt eine Operation an, bearbeitet oder löscht sie, oder er benennt einen Einsatz um, den ein anderer Betrachter in seiner Operation sieht.

## Hauptablauf

### Schreibvorgänge in place (`OperationWritesInPlaceE2eTest`, #576)

1. **Anlegen:** Auf `/operations` den Reload-Marker setzen und über das Modal eine Operation anlegen (AJAX-Zwilling von `POST /operations/create`).
2. **Bearbeiten:** Auf der Detailseite den Namen ändern und speichern, dann ohne Reload ein zweites Mal speichern.
3. **Löschen:** Auf der Detailseite löschen.

### Live-Sync der Detailseite (`OperationLiveSyncE2eTest`, #1115)

4. Beide Kontexte öffnen dieselbe Operation und warten, bis `window.krtLiveSync.subscribedTopics()` nicht leer ist. Kontext A benennt im Tab Verwaltung die Operation um und speichert; Kontext B schaut nur zu.

### Cross-Publish vom Einsatz (`OperationMissionCrossPublishLiveSyncE2eTest`, #1241)

5. Kontext B öffnet die Operation und wartet auf sein Abo `operation:{id}`. Kontext A öffnet den zugeordneten Einsatz, benennt ihn im Tab Verwaltung um und speichert.

## Erwartetes Ergebnis

- **Anlegen:** Die neue Operation steht in der ausgetauschten Liste; der Marker überlebt.
- **Bearbeiten:** Der erste Speichervorgang wird ohne Reload persistiert (per API gelesen), der zweite läuft ohne 409 — der Zwilling hat die frische Version ins Formular zurückgeschrieben.
- **Löschen:** Weil die Operation nicht mehr existiert, navigiert der Client zurück auf die Liste (Navigation nach AJAX); das Backend kennt sie nicht mehr.
- **Live-Sync:** B zeigt den neuen Namen im Sticky-Header **in place** (Abschnittsschlüssel `overview` im Raum `operation:{id}`).
- **Cross-Publish:** B zeigt den neuen Einsatznamen in der eingebetteten Einsatztabelle der Operation **in place**.

## Sonderfälle & Lehren

- **Operationen waren der erste Nicht-Einsatz-Nutzer des gemultiplexten `/ws/sync`.** Ein Abo gilt erst, wenn seine asynchrone Server-Autorisierung bestätigt ist — deshalb wartet jeder Test vor der Mutation auf `subscribedTopics()`, sonst könnte das Änderungssignal am Abo vorbeilaufen.
- **Warum die Kerndaten und nicht der Auszahlungs-Schalter:** Der Schalter bedient denselben Empfänger, bräuchte aber eine geseedete Auszahlungszeile (ein zugeordneter Einsatz mit eingechecktem Teilnehmer und tatsächlichen Zeiten).
- **Der einzige oberflächenübergreifende Fall:** Die Abschnitte `missions`/`finance` von `operation:{id}` sendet die Operationsseite selbst nie. Die Einsatzseite bildet `overview → missions` und `finance → finance` ab und veröffentlicht an `operation:{id}`, ohne es zu abonnieren.
