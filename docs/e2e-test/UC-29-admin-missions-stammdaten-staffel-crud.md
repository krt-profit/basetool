# UC-29 — Admin Missions-Stammdaten: Staffel anlegen & löschen

|                |                                                                                                                                          |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-29                                                                                                                                    |
| **Tag**        | `e2e`                                                                                                                                    |
| **Testklasse** | [`AdminMissionDataCrudE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AdminMissionDataCrudE2eTest.java) |
| **Spec**       | REQ-FE-002 (In-Place-Swap)                                                                                                               |

## Akteur

`test-admin` — die Seite `/admin/mission-data` ist ADMIN-gegated. Repräsentativer Vertreter des bislang ungetesteten Admin-Datenpflege-Clusters.

## Vorbedingungen

- Admin-`app_user`-Zeile vorhanden (`ensureIridiumMembership`).

## Auslöser

Der Admin legt über das Modal eine Staffel an und löscht sie über den Confirm-Dialog wieder.

## Hauptablauf

1. Navigiere zu `/admin/mission-data`.
2. **Anlegen:** `#add-squadron-btn` öffnet das Modal; `#sq-name`, `#sq-shorthand`, `#sq-desc` füllen; `#squadron-form`-Submit → `POST /admin/mission-data/squadrons` (`krtFetch`, Backend `POST /api/v1/squadrons`). Der Abschnitt `#squadrons-results` wird in place neu gerendert; die Zeile erscheint.
3. **Löschen:** `.delete-btn` der Zeile öffnet `#delete-confirm-modal`; `#delete-confirm-form`-Submit → `POST …/squadrons/{id}/delete` (Backend `DELETE /api/v1/squadrons/{id}`, Soft-Delete). Die Zeile verschwindet.

## Erwartetes Ergebnis

Nach dem Anlegen ist die Staffel in `#squadrons-results` sichtbar, nach dem Löschen weg (Default-Filter blendet Inaktive aus). Kein Schritt lädt die Seite neu (`window.__krtNoReload` überlebt), keiner erzeugt einen Fehler-Toast.

## Sonderfälle & Lehren

- **Hermetisch:** Der Flow legt seine eigene `E2E Mission Data Squad` an und löscht sie wieder — eine mitgliederlose Staffel löscht sauber (eine noch genutzte würde mit 409 abbrechen), es bleibt kein Rückstand für Schwester-Suiten.
- **Repräsentativ, nicht erschöpfend:** `/admin/mission-data` steht stellvertretend für den Admin-CRUD-Cluster; die reinen Lese-Adminseiten deckt der [`AdminPagesSmokeE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AdminPagesSmokeE2eTest.java) als Page-Load-Smoke ab (`/admin/locations`, `/admin/uex-data`, `/admin/announcement`, `/members`, `/organisation/leitung`, …).

