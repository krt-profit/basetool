# UC-23 — Job-Order-Bearbeiter & Notizen

|                |                                                                                                                                            |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-23                                                                                                                                      |
| **Tag**        | `e2e`                                                                                                                                      |
| **Testklasse** | [`JobOrderAssigneeNotesE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderAssigneeNotesE2eTest.java) |
| **Basis-Flow** | [UC-03](UC-03-job-order-anlegen.md)                                                                                                        |
| **Spec**       | [`orders-assignee-notes.md`](../specs/orders-assignee-notes.md) (REQ-ORDERS-013/014)                                                       |

## Akteur

Authentifizierter User — hier `test-admin` (erreicht LOGISTICIAN über die Rollenhierarchie). Bearbeitet seinen **eigenen** Eintrag, deckt also den Self-Pfad der Self-oder-Logistiker-Regel ab.

## Vorbedingungen

- IRIDIUM-Mitgliedschaft, ein Job-Order-Material, ein frischer `OPEN`-Auftrag (`createJobOrder`).
- Die User-ID des Akteurs (`getUserId`) für den Assignee-Read-back.

## Auslöser

Der User trägt sich auf der Auftragsdetailseite als Bearbeiter ein, hinterlegt eine Notiz, bearbeitet und löscht sie und trägt sich wieder aus — alles im Bearbeiter-Abschnitt (`#assignees-section`).

## Hauptablauf

1. Navigiere zu `/orders/{id}`.
2. **Eintragen:** Klick auf `oa-add-me` → `POST /orders/{id}/assignees` (AJAX, kein Reload). Der Abschnitt wird als Fragment neu gerendert; das Notiz-Bearbeiten-Icon (`oa-edit-note`) erscheint.
3. **Notiz anlegen:** `oa-edit-note` öffnet das Modal (`#assignee-note-modal`); Text in `#assignee-note-text` → `oa-save-note` postet `PUT /orders/{id}/assignees/{userId}/note` mit `{note, version}`.
4. **Notiz bearbeiten:** Erneut öffnen (der getauschte Button trägt die frische `data-version`), Text ändern, speichern.
5. **Notiz löschen:** `oa-delete-note` → `DELETE …/note?version=…`; die Zeile bleibt, Notiztext und Lösch-Icon verschwinden.
6. **Austragen:** `oa-remove-assignee` → `DELETE /orders/{id}/assignees/{userId}`; der Eintrag verschwindet.

## Erwartetes Ergebnis

Nach jedem Schritt spiegelt der per Backend gelesene `assignees[]`-Stand (`GET /api/v1/orders/{id}`) die Aktion wider: der User ist (nicht) eingetragen, die Notiz ist gesetzt/aktualisiert/`null`. Das neu gerenderte Fragment zeigt Name, Notiztext bzw. den leeren Zustand. Eine Notiz-Bearbeitung verändert nie die Auftrags-Version (eigene Kanten-`@Version`).

## Sonderfälle & Lehren

- **AJAX-Fragment-Swap statt Reload:** Jede Mutation tauscht `#assignees-section` per `outerHTML`. Auf den jeweiligen Call wird per `waitForResponse` gewartet; die UI-Assertions nutzen Playwrights Auto-Waiting (`isVisible`/`hasCount(0)`), die Zustands-Assertion liest deterministisch über die Backend-API zurück (analog UC-16).
- **Pfad-Mehrdeutigkeit:** Notiz-Calls (`…/note`) teilen den `/assignees`-Präfix mit Ein-/Austragen; die `waitForResponse`-Prädikate trennen sie über `!url.contains("/note")` plus die HTTP-Methode (POST vs. DELETE).
- **Versions-Durchreichung:** Das Notiz-Modal liest `data-version` vom angeklickten `oa-edit-note`-Button; weil der ganze Abschnitt nach jedem Save neu gerendert wird, trägt der Button immer die aktuelle Kanten-Version — kein manueller `data-version`-Sync, kein Stale-409 bei aufeinanderfolgenden Edits.
- **Delegierte Handler überleben den Swap:** Alle Controls hängen über `data-trigger` am `document`; das Neu-Lokalisieren der Buttons in jedem Schritt beweist nebenbei, dass die Bindings nach dem `outerHTML`-Tausch weiter funktionieren.
- **Gating:** Self-Eintrag/-Notiz funktioniert ohne Logistiker-Rolle; fremde Einträge erfordern LOGISTICIAN+ (Backend-Gate). Hier deckt `test-admin` den Self-Pfad ab.
