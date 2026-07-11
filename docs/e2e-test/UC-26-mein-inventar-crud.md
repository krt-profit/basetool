# UC-26 — Mein Inventar: Eintrag anlegen & löschen

|                |                                                                                                                                            |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-26                                                                                                                                      |
| **Tag**        | `e2e`                                                                                                                                      |
| **Testklasse** | [`PersonalInventoryCrudE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/PersonalInventoryCrudE2eTest.java) |
| **Spec**       | [`audit.md`](../specs/audit.md) (REQ-AUDIT-001, Mein Inventar), REQ-FE-001/002                                                             |

## Akteur

Der Standard-Testuser `test-admin`. **Mein Inventar** (`/personal-inventory`, Entität `PersonalInventoryItem`: freier Name + UEX-Ort + Menge, pro JWT-`sub`) ist rein benutzerbezogen und trägt keinen Mandanten-Scope — abzugrenzen von der persönlichen Lager-Sicht `/inventory/my` (Entität `InventoryItem`), die UC-13 abdeckt.

## Vorbedingungen

- Eine UEX-`city` mit numerischer `id_city` (`E2E Personal Inventory City`, `id_city=900001`) ist geseedet (`uex-catalog-seed.sql`) — nur so liefert der Orts-Typeahead (`/personal-inventory/uex-search`) einen Treffer, und nur so löst `resolveLocationName(CITY, id)` den Ort auf. Die Refinery-City des Katalogs hat bewusst keine `id_city` und ist daher hier unbrauchbar.
- Keine Staffel-Mitgliedschaft nötig (benutzerbezogen).

## Auslöser

Der User legt auf `/personal-inventory` einen Eintrag an (Ort über den Typeahead gewählt) und löscht ihn wieder.

## Hauptablauf

1. Navigiere zu `/personal-inventory`.
2. **Anlegen:** `pi-open-create` öffnet das Modal; `#krt-pi-name` + `#krt-pi-quantity` füllen; `#krt-pi-location-search` mit dem City-Namen füllen → auf den Typeahead-Treffer (`.krt-pi-typeahead-item`) klicken (setzt die versteckten `locationUexId`/`locationType`); Submit postet `POST /personal-inventory/add` (`krtFetch.write`).
3. **Ergebnis:** `#pi-results` wird in place neu gerendert; die Zeile mit dem Namen erscheint.
4. **Löschen:** `pi-open-delete` der Zeile öffnet `#krt-pi-delete-modal`; Bestätigen postet `POST /personal-inventory/{id}/delete`; die Zeile verschwindet in place.

## Erwartetes Ergebnis

Nach dem Anlegen ist der Eintrag in `#pi-results` sichtbar **und** per Backend-Read (`GET /api/v1/personal-inventory?q=…`) persistiert; nach dem Löschen ist die Zeile weg. Keine der beiden Mutationen lädt die Seite neu (`window.__krtNoReload` überlebt), keine erzeugt einen Fehler-Toast.

## Sonderfälle & Lehren

- **UEX-City-Seed als Blocker:** Ohne eine `city`-Zeile mit `id_city` bleibt der Typeahead leer und das Anlegen scheitert — der Seed ist zwingend, nicht optional.
- **Bespoke-Typeahead statt Combobox:** Der Ort ist ein eigener Debounce-Typeahead (nicht `krt-searchable-select`); ausgewählt wird per Klick auf den gerenderten Ergebnis-Button, der die versteckten Felder befüllt. Ohne gewählten Ort blockiert die JS den Submit clientseitig.
- **Abgrenzung:** Nicht mit `/inventory/my` (UC-13) verwechseln — andere Entität, anderer Controller, andere Route.

