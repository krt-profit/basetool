# UC-40 — Bereichs-Hierarchie & Ernennungen

|                |        |
|----------------|--------|
| **ID**         | UC-40  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`OrgHierarchyVisibilityMatrixE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrgHierarchyVisibilityMatrixE2eTest.java) · [`RoleAppointmentMatrixE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RoleAppointmentMatrixE2eTest.java) |
| **Spec**       | [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) (REQ-ORG-015/-016/-017) · [`role-model.md`](../specs/role-model.md) (REQ-ROLE-004) |

## Akteure

- `test-admin` — seedet Bereiche und Ernennungen.
- `test-bereich` — Realm-Nutzer nur mit `KRT Member` und ohne Staffel; wird Bereichsleiter von Bereich A.
- `test-appoint-bl` (Bereichsleiter dieser Suite) und `test-appoint-tgt` (Ziel ohne Staffel) — eigene Realm-Nutzer der Ernennungsmatrix.
- `test-member` — einfaches Staffelmitglied.

## Vorbedingungen

- **Hierarchie:** Der Admin legt zwei Bereiche an und je einen Lagereintrag im Besitz jedes Bereichs (Anlegen im Auftrag, REQ-ORG-016, je eigenes Material) und gibt `test-bereich` die Rolle `LEITER` in Bereich A.
- **Ernennungen:** `test-appoint-bl` wird Bereichsleiter von Bereich A dieser Suite; `test-member` bekommt nur sicher eine IRIDIUM-Mitgliedschaft.

## Auslöser

Ein Bereichsleiter liest Lager-Daten oder ernennt Rollen.

## Hauptablauf

### Bereichs-Sichtbarkeit (`OrgHierarchyVisibilityMatrixE2eTest`, #700)

1. Per API: `test-bereich` und `test-member` lesen die Lager-Daten beider Bereiche; `test-bereich` ruft einen ADMIN-Endpunkt (die Admin-Liste der Org-Hierarchie) auf.
2. In der UI: `test-bereich` lädt `/inventory/all`.

### Ernennungsmatrix (`RoleAppointmentMatrixE2eTest`, epic #800)

3. Per API als ernennender Nutzer: der Bereichsleiter ernennt einen Koordinator im eigenen Bereich, jemanden im fremden Bereich und einen weiteren Bereichsleiter; ein einfaches Mitglied ernennt eine Bereichsrolle und vergibt einen Staffelrang; der Admin gibt einem Staffelmitglied einen Silo-Leitungsrang; die Leitung-Ansicht (`GET /api/v1/leadership`) wird nach der Ernennung gelesen.

## Erwartetes Ergebnis

- **Sichtbarkeit:** Der Bereichsleiter sieht den Bestand seines Bereichs, nie den des fremden; ein einfaches Staffelmitglied sieht keinen Bereichsbestand; der ADMIN-Endpunkt bleibt **403** (REQ-ORG-015). In der UI erscheint nur die Gruppenzeile des eigenen Bereichs.
- **Ernennungen:** Koordinator im eigenen Bereich — erlaubt; fremder Bereich und weiterer Bereichsleiter — verweigert; einfaches Mitglied — **403** auf beiden Endpunkten; Silo-Rang für ein Staffelmitglied — **400** vom Service-Wächter, bevor der DB-Trigger (V165/V187) feuert (REQ-ORG-017). Der ernannte Bereichsleiter steht in der Leitung-Ansicht (REQ-ROLE-004).

## Sonderfälle & Lehren

- **Eigene Profile statt geteilter Nutzer.** Eine Leitungsrolle gibt einem Nutzer für den Rest des sequentiell laufenden Stacks eine Bereichsmitgliedschaft. `test-none` darf nie Mitglied werden — `RefineryOrderTenancyE2eTest` und `InventoryTenancyE2eTest` verlassen sich darauf. Deshalb haben Hierarchie- und Ernennungstest eigene Nutzer; `test-member` ist nur ein abgewiesenes Ziel und wird nie verändert.
- **Kaskade und OL-Reichweite** (Bereichsleiter → untergeordnete Staffel/SK) prüft die Unit-Ebene (`ScopeKernelTest.CascadingScopeTests`); die Ernennungslogik zusätzlich `OrgRoleManagementSecurityServiceTest` und `OrgHierarchyMigrationTest`.
- **Mutierende Tests**, deshalb `@Tag("e2e")` und nie gegen ein geteiltes Staging.
