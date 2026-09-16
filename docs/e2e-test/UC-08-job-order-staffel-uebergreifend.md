# UC-08 — Job Order staffel-übergreifend (Staffel A bestellt, Staffel B liefert)

|                |                                                                                                                                          |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-08                                                                                                                                    |
| **Tag**        | `e2e`                                                                                                                                    |
| **Testklasse** | [`CrossStaffelJobOrderE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/CrossStaffelJobOrderE2eTest.java) |
| **Basis-Flow** | [UC-03](UC-03-job-order-anlegen.md) · Scope-Regeln: [Rollen & Scope](rollen-und-scope.md)                                                |

## Akteure

- **User der Staffel A** (z. B. KRT Member oder Logistician von A) — legt den Job Order an.
- **User der Staffel B** (KRT Member oder Logistician von B) — verknüpft eigenes, B-besessenes Inventar mit dem Auftrag.

## Vorbedingungen

- Zwei Staffeln A und B existieren; je ein Test-User mit Mitgliedschaft.
- Ein Job-Order-Material (`isJobOrder=true`).
- Staffel B besitzt einen Lagereintrag dieses Materials (Owner = B, Qualität ≥ `minQuality`).

## Auslöser

Staffel A braucht Material, das Staffel B liefern soll.

## Hauptablauf

1. **Staffel A** legt unter `/orders/create` einen Job Order an: `responsibleOrgUnitId` = A (bearbeitende Einheit, muss profit-eligible sein), `requestingOrgUnitId` = A, Material + Menge.
2. **Staffel B** verknüpft einen eigenen Lagereintrag mit dem Auftrag (`POST /api/v1/inventory` mit `jobOrderId` = A's Auftrag; das Item bleibt B-besessen). Die Verknüpfung ist nicht sichtbarkeits-gegatet — B muss den (A-privaten) Auftrag dafür nicht in der eigenen Warteschlange sehen.
3. **Staffel A** öffnet die Auftragsdetailseite `/orders/{id}` und sieht das von B verknüpfte Material im Auftrags-Kontext.

## Erwartetes Ergebnis

- Der Job Order ist **A-verantwortet** (Responsible = A) und damit für A sicht-/bearbeitbar; B trägt über die ungegated Inventar-Verknüpfung bei, ohne den Auftrag sehen zu müssen (Auftrags-Sichtbarkeit nach `responsibleOrgUnit.kind`, REQ-ORG-003 / [UC-18](UC-18-job-order-mandanten-sichtbarkeit.md)).
- B's verknüpfter Lagereintrag erscheint **im Auftrags-Kontext** (`findByJobOrderIdOrdered`, ungegated) und zählt auf die offene Menge des `JobOrderMaterial` ein.
- B's Lagereintrag erscheint **nicht** in der Lager-View von Staffel A (`findGlobalByFilters` bleibt gegated) — kein Datenleck.

## Sonderfälle & Lehren

- **Cross-Staffel-Beitrag über verknüpftes Inventar:** Staffel-übergreifend ist nicht die Auftrags-_Sichtbarkeit_ (die richtet sich nach `responsibleOrgUnit.kind`, REQ-ORG-003), sondern das **verknüpfte Inventar** im Auftrags-Kontext — `findByJobOrderIdOrdered` ist ungegated, `findGlobalByFilters` bleibt gegated. Genau dieser Split ermöglicht „A bestellt, B liefert", ohne B's Lager in A's View zu leaken.
- **Zwei OrgUnit-Referenzen:** `responsible_org_unit_id` (die **bearbeitende** Einheit — muss profit-eligible sein, steuert die Sichtbarkeit) vs. `requesting_org_unit_id` (Auftraggeber, editierbar — akzeptiert jede aktive OrgUnit). Das frühere `creating_org_unit_id` ist entfallen.
- **Profit-Eligibility-Pflicht:** `POST /api/v1/orders` verlangt ein `responsibleOrgUnitId`, das auf eine profit-eligible Einheit auflöst (sonst 400). Der Stack-Bootstrap schaltet IRIDIUM einmalig profit-eligible — siehe UC-06/Seeder.
- **Split-Repository** ist der Kern der Isolation: ungegated im Auftrags-Kontext, gegated in der Lager-View.
