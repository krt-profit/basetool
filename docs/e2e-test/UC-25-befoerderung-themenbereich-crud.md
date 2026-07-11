# UC-25 — Beförderung: Themenbereich anlegen/umbenennen/löschen

|                |                                                                                                                                      |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-25                                                                                                                                |
| **Tag**        | `e2e`                                                                                                                                |
| **Testklasse** | [`PromotionTopicCrudE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/PromotionTopicCrudE2eTest.java) |
| **Spec**       | [`audit.md`](../specs/audit.md) (REQ-AUDIT-001, Beförderung), REQ-FE-001/005                                                         |

## Akteur

Ein Offizier (`test-officer`, Realm-Rolle `Officer`), der IRIDIUM-Staffel zugeordnet. **Bewusst kein Admin:** die Beförderungsseiten sind pro Staffel und über das Promotion-Flag der aktiven Staffel gegated. Ein Admin ohne Staffel-Pin landet im All-Staffeln-Modus, in dem die CRUD-Bedienelemente und das JS-Modul gar nicht gerendert werden. Ein Nicht-Admin ist nie im All-Staffeln-Modus, also rendert der IRIDIUM-Offizier den vollen Editor ohne Pin-Plumbing.

## Vorbedingungen

- `test-officer` ist Mitglied der IRIDIUM-Staffel (`assignStaffelMembership`) — die Staffel hat das Beförderungs-Feature per Default aktiviert, und das Anlegen stempelt die besitzende Staffel aus der Einzel-Mitgliedschaft.

## Auslöser

Der Offizier legt auf `/promotion/admin/topics` einen Themenbereich an, benennt ihn um und löscht ihn wieder.

## Hauptablauf

1. Navigiere zu `/promotion/admin/topics`.
2. **Anlegen:** `pa-open-create-topic` öffnet `#modal-create-topic`; `#ct-name` füllen → `pa-create-topic` postet `POST /api/proxy/promotion/topics`. Das Fragment `#pa-topics-results` wird in place neu gerendert (`krt:swapped`), die neue Karte trägt den Namen (`.admin-topic-name`).
3. **Umbenennen:** `pa-edit-topic` der Karte öffnet `#modal-edit-topic`; `#et-name` ändern → `pa-update-topic` postet `PUT …/topics/{id}`. Der neue Name erscheint, der alte verschwindet.
4. **Löschen:** `pa-delete-topic` → KRT-Confirm (`.krt-confirm-overlay .krt-confirm-ok`) → `DELETE …/topics/{id}`; die Karte verschwindet.

## Erwartetes Ergebnis

Nach jedem Schritt spiegelt das neu gerenderte `#pa-topics-results`-Fragment die Aktion (Karte vorhanden / umbenannt / weg). Kein Schritt lädt die Seite neu (Marker `window.__krtNoReload` überlebt), keiner erzeugt einen Fehler-Toast oder ein liegengebliebenes Confirm-Overlay.

## Sonderfälle & Lehren

- **Rollenwahl:** Der Offizier-Pfad umgeht das All-Staffeln-Modus-Problem des Admins vollständig — kein Staffel-Pin, kein `X-Active-Org-Unit-Id`-Header nötig.
- **Fragment-Swap-Wait:** Wie in `OrgChartPositionCrudE2eTest` wartet ein einmaliger `krt:swapped`-Listener, der auf den Container `#pa-topics-results` scoped ist, auf den Commit des neuen Fragments (mit frischen `data-pa-version`) — 30-s-Budget wegen der geteilten Stack-Last.
- **Audit:** Die drei Mutationen sind auditierte Beförderungs-Aktivitäten (REQ-AUDIT-001); der Flow ist der erste E2E-Nachweis dieser Area.

