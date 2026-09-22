# UC-15 — Job Order bearbeiten

|                |                                                                                                                          |
|----------------|--------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-15                                                                                                                    |
| **Tag**        | `e2e`                                                                                                                    |
| **Testklasse** | [`JobOrderEditE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderEditE2eTest.java) |
| **Basis-Flow** | [UC-03](UC-03-job-order-anlegen.md)                                                                                      |

## Akteur

Authentifizierter User mit der Rolle LOGISTICIAN (oder OFFICER/ADMIN über die Rollenhierarchie) — hier `test-admin`.

## Vorbedingungen

- IRIDIUM-Mitgliedschaft (`ensureIridiumMembership`).
- Ein Job-Order-Material (`ensureJobOrderMaterial`) und ein MATERIAL-Auftrag, der es mit Menge `100.0` anfragt (`createJobOrder`).

## Auslöser

Der User öffnet das Bearbeiten-Modal auf der Auftragsdetailseite `/orders/{id}`.

## Hauptablauf

1. Navigiere zu `/orders/{id}`.
2. Öffne das (LOGISTICIAN-gegatete) Bearbeiten-Modal über den Trigger `[data-trigger='open-modal-display'][data-modal-id='edit-modal']`. Das Modal ist vom Page-Controller mit Materialzeilen, Handle und Kommentar vorbefüllt.
3. Wähle in der Material-Combobox des Modals die erste angebotene Option, setze die Menge (`materials[0].amount`) auf `250` und einen eindeutigen Kommentar (`#edit-comment`).
4. Speichern (Submit-Button im `#edit-modal`) → `POST /orders/{id}/update` → Backend `PUT /api/v1/orders/{id}`.

## Erwartetes Ergebnis

Ein Read-Back über `GET /api/v1/orders/{id}` — gepollt, bis die neue Menge erscheint — liefert Menge `250` und den neuen Kommentar.

## Sonderfälle & Lehren

- **Optimistic Locking:** Das Modal trägt das versteckte `version`-Feld; das Backend lehnt einen veralteten Stand mit 409 ab.
- **Read-only `responsibleOrgUnit`:** Die bearbeitende Einheit ist im Modal nur lesend — sie wird ausschließlich über den Umschreib-Flow (`PATCH /api/v1/orders/{id}/responsible-org-unit`) geändert, nicht über das reguläre Update.
- **Warum die erste Material-Option:** Der Material-Picker baut sich aus einer 10 Minuten gecachten Material-Liste, die das frisch geseedete Material noch nicht enthalten muss (unter WebKit beobachtet); dann fiele die Vorauswahl auf einen leeren Picker. Welches Material gewählt wird, ist egal — geprüft wird nur, dass Menge und Kommentar ankommen.
- **Pollen statt auf Browser-Events warten:** Das Bearbeiten ist ein voller POST → Redirect → GET. Unter CI-Last verwirft WebKit sowohl das Navigations-Event (`awaitFormPost`) als auch das POST-Response-Event, obwohl der Schreibvorgang committet ist. Der Test pollt deshalb das Backend; ein Klick, der nie gepostet hat, lässt die Menge unverändert, und der Poll schlägt trotzdem fehl. Das vermeidet zugleich die Strict-Mode-Mehrdeutigkeit, dass der Kommentar auf der neu geladenen Seite zweimal steht (Anzeige und vorbefülltes Textfeld).
