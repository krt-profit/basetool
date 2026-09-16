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
2. Öffne das (LOGISTICIAN-gegatete) Bearbeiten-Modal über den Trigger `open-modal-display` / `data-modal-id="edit-modal"`. Das Modal ist vom Page-Controller mit Materialzeilen, Handle und Kommentar vorbefüllt.
3. Setze die Materialmenge (`materials[0].amount`) auf `250` und einen eindeutigen Kommentar (`#edit-comment`).
4. Speichern (`#edit-modal button[type=submit]`) → `POST /orders/{id}/update` → Backend `PUT /api/v1/orders/{id}`.

## Erwartetes Ergebnis

Die neu geladene Detailseite zeigt in der „Benötigt"-Spalte `250.000` und den neuen Kommentar.

## Sonderfälle & Lehren

- **Optimistic Locking:** Das Modal trägt das versteckte `version`-Feld; das Backend lehnt einen veralteten Stand mit 409 ab. Der Test lädt nach dem Speichern neu, statt den DOM-Stand weiterzuverwenden.
- **Read-only `responsibleOrgUnit`:** Die bearbeitende Einheit ist im Modal nur lesend — sie wird ausschließlich über den Umschreib-Flow (`PATCH …/responsible-org-unit`) geändert, nicht über das reguläre Update. Das Update-DTO sendet `responsibleOrgUnitId = null`.
- **Locale-robuste Assertion:** Statt auf lokalisierten Spaltentext zu prüfen, prüft der Test, dass die Materialzeile den Teilstring `250` enthält (die ursprüngliche `100` und der Lagerstand `0` erzeugen ihn nicht) plus den eindeutigen Kommentar-String.
- **Post-Submit-Settle:** Der volle Redirect wird über `awaitFormPost` abgewartet, bevor neu navigiert wird (sonst bricht WebKit den In-Flight-Redirect ab — HTTP/2 `INTERNAL_ERROR`).
