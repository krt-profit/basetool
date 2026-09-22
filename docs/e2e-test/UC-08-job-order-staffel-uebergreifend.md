# UC-08 — Job Order staffel-übergreifend (Staffel A bestellt, Staffel B liefert)

|                |                                                                                                                                          |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-08                                                                                                                                    |
| **Tag**        | `e2e`                                                                                                                                    |
| **Testklasse** | [`CrossStaffelJobOrderE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/CrossStaffelJobOrderE2eTest.java) |
| **Basis-Flow** | [UC-03](UC-03-job-order-anlegen.md) · Scope-Regeln: [Rollen & Scope](rollen-und-scope.md)                                                |

## Akteure

- **`test-officer`** (OFFICER, Heimat-Staffel IRIDIUM = Staffel A) — treibt die UI.
- **`test-member`** (KRT Member, Heimat einer frisch angelegten Staffel B „E2E JobOrder B") — Besitzer des verknüpften Lagereintrags; wird nur per REST verwendet.

## Vorbedingungen

Nur im ephemeren Modus per REST geseedet (`STACK.managesStack()`):

- Staffel B (`createSquadron`) und die Mitgliedschaften beider Nutzer (`assignStaffelMembership`).
- Ein Job-Order-Material und eine Location.
- Ein Job Order mit IRIDIUM als bearbeitender und anfragender Einheit (`createJobOrder`).
- Ein Lagereintrag, den `test-member` **mit `jobOrderId` = diesem Auftrag** anlegt (`createInventoryItemForJobOrder`) — der Resolver stempelt ihn damit auf Staffel B.

## Auslöser

Staffel A will einen Auftrag erfüllen, zu dem Staffel B Material beigesteuert hat.

## Hauptablauf

1. **Auftragskontext (UI):** `test-officer` meldet sich an, öffnet `/orders/{id}?tab=handovers` und das Handover-Modal (`order-handover-open`); der Test wartet auf den Lazy-Fetch des verknüpften Inventars und fügt eine Zeile hinzu (`#add-handover-item-btn`).
2. **Lager-View (API):** `GET /api/v1/inventory/material/{materialId}` einmal als `test-member`, einmal als `test-officer`.

## Erwartetes Ergebnis

- Im Auftragskontext bietet das Dropdown `items[0].inventoryItemId` genau den B-besessenen Eintrag an — A kann den Auftrag damit erfüllen.
- B's eigene Lager-View listet den Eintrag (Vorbedingung, per `assumeTrue`).
- A's Lager-View listet ihn **nicht** — kein Leck aus dem Auftragskontext in die staffel-gescopte Lager-Ansicht.

## Sonderfälle & Lehren

- **Cross-Staffel-Beitrag über verknüpftes Inventar:** Staffel-übergreifend ist nicht die Auftrags-_Sichtbarkeit_ (die richtet sich nach `responsibleOrgUnit.kind`, REQ-ORG-003), sondern das **verknüpfte Inventar** im Auftrags-Kontext — `findByJobOrderIdOrdered` ist ungegated, `findGlobalByFilters` bleibt gegated. Genau dieser Split ermöglicht „A bestellt, B liefert", ohne B's Lager in A's View zu leaken.
- **Zwei OrgUnit-Referenzen:** `responsible_org_unit_id` (die **bearbeitende** Einheit — muss profit-eligible sein, steuert die Sichtbarkeit) vs. `requesting_org_unit_id` (Auftraggeber, editierbar — akzeptiert jede aktive OrgUnit). Das frühere `creating_org_unit_id` ist entfallen.
- **Profit-Eligibility-Pflicht:** `POST /api/v1/orders` verlangt ein `responsibleOrgUnitId`, das auf eine profit-eligible Einheit auflöst (sonst 400). Der Stack-Bootstrap schaltet IRIDIUM einmalig profit-eligible — siehe UC-06/Seeder.
- **Split-Repository** ist der Kern der Isolation: ungegated im Auftrags-Kontext, gegated in der Lager-View.
