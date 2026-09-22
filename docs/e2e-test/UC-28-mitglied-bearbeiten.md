# UC-28 — Mitglied bearbeiten (In-Place-Save)

|                |                                                                                                                                    |
|----------------|------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-28                                                                                                                              |
| **Tag**        | `e2e`                                                                                                                              |
| **Testklasse** | [`MemberEditInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MemberEditInPlaceE2eTest.java) |
| **Spec**       | REQ-FE-007 (In-Place-Save), [`role-model.md`](../specs/role-model.md)                                                              |

## Akteur

`test-admin` — die `/members`-Verwaltung ist ADMIN-gegated. Bearbeitet wird `test-member`.

## Vorbedingungen

- `test-member` ist im Backend materialisiert (`getUserId`), damit die Editseite auflöst.
- Der Rang ist ein Top-Level-Attribut und unabhängig von der Staffel-Mitgliedschaft editierbar — der Test mutiert also keinen Mitgliedschaftszustand, auf den Schwester-Suiten angewiesen sind.

## Auslöser

Der Admin öffnet die Editseite des Mitglieds und speichert einen geänderten Rang in place.

## Hauptablauf

1. Navigiere zu `/members/{id}/edit?source=members`.
2. Rang im Select `select[name="rank"]` auf einen Zielwert setzen.
3. **Speichern:** `#member-edit-form`-Submit → `POST /members/{id}/edit` mit `X-Requested-With` (`krtFetch`) → Backend `PUT /api/v1/users/{id}/attributes`. Kein Reload.

### Zweite Staffel hinzufügen (clientseitig)

4. Als Zielmitglied dient `test-member`, der ohne Staffel-Mitgliedschaft geseedet ist: der Button „Zweite Staffel hinzufügen" ist sichtbar, Slot 2 versteckt. Klick auf den Button.

## Erwartetes Ergebnis

Der Save landet: kein Fehler-Toast, kein Optimistic-Lock-Reload-Dialog (`.krt-confirm-overlay`), der Marker `window.__krtNoReload` überlebt, und der per Backend gelesene Rang (`GET /api/v1/users/{id}`) entspricht dem gesetzten Wert.

Nach dem Klick auf „Zweite Staffel hinzufügen" ist der zweite Staffel-Slot tatsächlich sichtbar (REQ-ORG-017). Das ist ein reiner Client-Toggle ohne Schreibzugriff. Regressionsschutz für die Falle Klasse vs. Inline-Stil (ADR-0093): Slot 2 startet über die Klasse `krtm-hidden` verborgen; ein früherer Versuch, das per Inline-`style.display` zu überschreiben, ließ den Button verschwinden, während der Slot versteckt blieb.

## Sonderfälle & Lehren

- **Bisherige Lücke:** `RoleAppointmentMatrixE2eTest` prüft nur die Backend-Appointment-API, `RolePermissionsE2eTest` nur das `sec:authorize`-Gating der Auftragssicht — die In-Place-Bearbeitung der Mitgliederseite selbst war ungetestet.
- **Persistenz-Read-back:** Der Zielrang wird deterministisch über die Backend-API zurückgelesen (analog `AdminSettingsInPlaceE2eTest`), nicht über das DOM, das den Client-Writeback rennen könnte.
- **Keine Mitgliedschafts-Mutation:** Nur der Rang wird geändert; die Staffel-Sektion (`staffelDetailLoaded`) bleibt unangetastet, damit der geteilte Fixture-User sauber bleibt.
