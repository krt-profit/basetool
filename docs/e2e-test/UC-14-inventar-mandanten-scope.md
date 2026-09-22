# UC-14 — Inventar: Mandanten-Scope (wer sieht/erstellt/bearbeitet was)

|                |                                                                                                                                  |
|----------------|----------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-14                                                                                                                            |
| **Tag**        | `e2e`                                                                                                                            |
| **Testklasse** | [`InventoryTenancyE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventoryTenancyE2eTest.java) |

## Akteur

Fünf Profile, die alle Mitgliedschafts-Konstellationen abdecken — Admin (IRIDIUM), Staffel-A-Mitglied, Staffel-B + SK-X-Mitglied, reines SK-X-Mitglied, Mitglied **ohne jede** Zugehörigkeit. Einen nicht angemeldeten Besucher gibt es seit ADR-0159 nicht mehr als Viewer; er erreicht das Lager gar nicht ([UC-12](UC-12-mitgliederbereich.md)).

## Vorbedingungen

Die direkte Lager-View ist **strict-staffel** (REQ-ORG-003): ein Eintrag ist auf seinen `owning_org_unit_id`-Pool beschränkt und verlässt ihn nie. Per REST geseedet (nur ephemerer Modus):

- **Nutzer/Mitgliedschaften:** `test-admin` (ADMIN, Staffel A), `test-member` (nur Staffel A), `test-both` (Staffel B **+** SK X), `test-sk` (nur SK X), `test-none` (keine Mitgliedschaft). SK-Mitgliedschaft via `POST /api/v1/special-commands/{id}/members/{userId}`; Staffel via `PATCH /api/v1/users/{id}/memberships` (`assignStaffelMembership`). `test-both`, `test-sk` und `test-none` sind dedizierte Realm-Nutzer (`realm-export.e2e.json`) — bewusst nicht der geteilte `test-officer`, damit die zusätzliche SK-Mitgliedschaft nicht in Schwester-Suites leakt, die `test-officer` als Einzelmitglied erwarten.
- **Je ein nicht-persönlicher Eintrag pro Eigentümer**, jeweils auf **eigenem Material** (Material ↔ Eigentümer 1:1): Staffel A, Staffel B, SK X und ein **eigentümerloser** (`owningOrgUnit = null`) Eintrag, erfasst vom mitgliedschaftslosen Nutzer. Erstellt **als** der im Zielpool beheimatete Nutzer, damit der Create-Resolver den gewünschten Eigentümer stempelt.

## Auslöser

Jeder Viewer ruft die gescopten Inventar-Endpunkte ab bzw. öffnet `/inventory/all`.

## Hauptablauf & Erwartetes Ergebnis

UI treiben, **per API verifizieren** (wie UC-08): die Matrix wird durch Abruf der gescopten Endpunkte als jeweiliger Nutzer über den `BackendSeeder` geprüft; die Grenze wird zusätzlich über die echte `/inventory/all`-UI für ein reines Staffel-Mitglied gefahren (Gruppenzeile der eigenen Staffel sichtbar, die einer fremden nicht). Der Admin-Pin wird über den `X-Active-Org-Unit-Id`-Header gesetzt, den das Frontend relayt.

Sichtbarkeitsraster im globalen Lager (`/inventory/all`) für einen Eintrag im Besitz von OrgUnit O:

|             Viewer             |          sieht O           |
|--------------------------------|----------------------------|
| Admin ohne Pin                 | alle (inkl. eigentümerlos) |
| Admin mit Pin = O              | nur O                      |
| Mitglied von O                 | ✓                          |
| Mitglied einer anderen OrgUnit | ✗                          |
| Staffel **+** SK-Mitglied      | Vereinigung beider Pools   |
| mitgliedschaftslos             | ✗                          |

Zusätzlich abgedeckt:

- **Persönliche View** (`/inventory/my`): rein nutzer-gescopt — jeder sieht nur seine eigenen Zeilen, unabhängig vom Eigentümer; eigentümerlose Stocks sieht der Ersteller hier, im globalen Lager aber nur der ungepinnte Admin.
- **Create-Stamping** (REQ-ORG-004): 1 Mitgliedschaft → Auto-Stamp; mehrere ohne Auswahl → 400; gültige Auswahl → übernommen; fremde Auswahl → 400; mitgliedschaftslos ohne Auswahl → eigentümerlos.
- **Edit-Gate** (`canEditInventoryItem`): ein **Nicht-Eigentümer** außerhalb des OrgUnit-Scopes kann nicht ausbuchen (403), ein Mitglied der besitzenden OrgUnit schon.
- **Eigentümer-Escape** (REQ-ORG-011): der **Eigentümer** (`inventory_item.user`) eines Eintrags darf ihn **immer** ausbuchen/bearbeiten, auch nachdem er die besitzende OrgUnit verlassen hat, während der Eintrag noch auf diese OrgUnit gebucht ist. Geseedet, indem `test-member` (Staffel A) kurzfristig einer frischen SK beitritt, einen darauf gestempelten Eintrag anlegt und die SK wieder verlässt — die SK-Mitgliedschaft war nicht die *letzte*, daher demotet der `InventoryOrgUnitReconciler` den Eintrag nicht auf `NULL` (REQ-INV-004), und `test-member` besitzt nun einen SK-gestempelten Eintrag, ohne Mitglied der SK zu sein. Ein Nicht-Eigentümer außerhalb dieser SK bleibt 403.

## Sonderfälle & Lehren

- **SK ist eine vollwertige besitzende OrgUnit fürs Inventar.** Die ältere Annahme „SK als Eigentümer 400t" gilt **nicht** mehr fürs `inventory_item`: V103 hat die Legacy-Spalte `owning_squadron_id` dort entfernt, `owning_org_unit_id` ist seit V132 nullable. SK-eigene und eigentümerlose Einträge sind anlegbar.
- **`memberOrgUnitIds` = Vereinigung aller Mitgliedschaften** (Staffel **und** SK, Quelle `org_unit_membership`). Ein Staffel+SK-Mitglied sieht beide Pools.
- **Admin-Pin verengt.** Ein gepinnter Admin ist wie ein Mitglied der gepinnten OrgUnit gescopt — nicht allsehend; eigentümerlose Zeilen sieht nur der **ungepinnte** Admin (und der Eigentümer in seiner persönlichen View).
- **Keycloak-Rolle ≠ Mitgliedschaft.** `test-none` trägt die Basisrolle „KRT Member", hat aber keine `org_unit_membership` — der Scope-Prädikat-Pfad ist damit leer (sieht nichts im globalen Lager).
- **Verifikation via API.** Die gruppierte Lager-Ansicht lädt Stacks lazy; gegen die gescopten Endpunkte als verschiedene Nutzer zu assertieren ist die etablierte, race-freie Methode für Tenancy-Grenzen (vgl. UC-08/UC-09).
