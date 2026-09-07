# Rollen & Mandanten-Scope (Referenz)

Diese Referenz hält die Rollen- und Tenancy-Regeln fest, auf denen die rollen- und staffel-/SK-spezifischen Use Cases (UC-08 ff.) aufbauen. Quelle der Wahrheit sind die `@PreAuthorize`-Annotationen im Backend und [`../../ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md); diese Datei fasst nur das für die E2E-Szenarien Relevante zusammen.

## Rollen

|                                    Rolle                                     |                              Herkunft                               |                                                                                               Kurz                                                                                               |
|------------------------------------------------------------------------------|---------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Admin**                                                                    | Keycloak-Rolle                                                      | Globaler Scope, umgeht alle OrgUnit-Checks.                                                                                                                                                      |
| **Officer**                                                                  | Keycloak-Rolle                                                      | Erbt `LOGISTICIAN` + `MISSION_MANAGER` (Hierarchie), aber **staffel-scoped** über `canEditOrgUnit(...)`.                                                                                         |
| **Logistician**                                                              | Kontextuell: `org_unit_membership.is_logistician`                   | Lager- & Auftragsverwaltung. Flache Rolle wird vom JWT-Konverter befördert, wenn das Flag auf *irgendeiner* Mitgliedschaft `true` ist; das Per-OrgUnit-Scoping erfolgt über `OwnerScopeService`. |
| **Einsatzleiter** (Keycloak-Rolle `Mission Manager`, Code `MISSION_MANAGER`) | Kontextuell: `org_unit_membership.is_mission_manager`               | Einsatz-Verwaltung; gleiche Beförderungslogik.                                                                                                                                                   |
| **SK Lead**                                                                  | Kontextuell: `org_unit_membership.is_lead` (nur auf einer SK-Zeile) | Darf in *diesem einen* SK Mitglieder verwalten — sonst nichts.                                                                                                                                   |
| **KRT Member**                                                               | Basis-User                                                          | `HANGAR_READ/WRITE`, `MISSION_READ`. Keine erhöhten Rechte.                                                                                                                                      |
| **Kein Rollenträger** (`ROLE_NO_ROLE`)                                       | Konto ohne Anwendungsrolle                                          | Erreicht nichts — `403 NO_ROLE` vor jedem Handler (`REQ-SEC-053`). Der Vorgänger hieß `Guest` und war unauthentifiziert; ohne Anmeldung gibt es seit ADR-0159 nur Startseite und Rechtsseiten.   |

**Hierarchie:** `ADMIN > LOGISTICIAN`, `ADMIN > MISSION_MANAGER`, `OFFICER > LOGISTICIAN`, `OFFICER > MISSION_MANAGER`.

## Rollen × Flow-Matrix (Schreib-Operationen)

|                   Flow                   |       KRT Member        |      Logistician       |  Einsatzleiter  | Officer | Admin |
|------------------------------------------|-------------------------|------------------------|-----------------|---------|-------|
| Einsatz anlegen (UC-02)                  | ✓                       | ✓                      | ✓               | ✓       | ✓     |
| Job Order anlegen (UC-03)                | ✓                       | ✓                      | ✓               | ✓       | ✓     |
| Job Order bearbeiten (UC-15)             | ✗                       | ✓                      | ✗               | ✓       | ✓     |
| Job-Order-Status ändern (UC-16)          | ✗                       | ✓                      | ✗               | ✓       | ✓     |
| Refinery Order anlegen (UC-04)           | ✓ (Owner = self)        | ✓ (Owner frei wählbar) | ✓               | ✓       | ✓     |
| Schiff in Hangar (UC-05)                 | ✓                       | ✓                      | ✓               | ✓       | ✓     |
| Eigenes Inventar an Job Order verknüpfen | ✓ (nur eigenes)         | ✓ (fremder Owner)      | ✓ (nur eigenes) | ✓       | ✓     |
| Job-Order-Handover (UC-06)               | ✗                       | ✓                      | ✗               | ✓       | ✓     |
| Job Order / Item-Order löschen           | ✗                       | ✗                      | ✗               | ✗       | ✓     |
| Einsatz/Operation anlegen                | ✗                       | ✗                      | ✓               | ✓       | ✓     |
| SK anlegen / umbenennen / löschen        | ✗                       | ✗                      | ✗               | ✗       | ✓     |
| SK-Mitglieder verwalten                  | nur als **Lead** des SK | –                      | –               | ✗       | ✓     |

Die Gates verbatim: Einsatz `isAuthenticated()`, Job Order `permitAll()`, Refinery Order + Inventar `isAuthenticated()` (fremder Owner nur `isLogisticianOrAbove`), Handover `hasRole('LOGISTICIAN') or hasRole('OFFICER') or hasRole('ADMIN')`, Job Order bearbeiten/Status `hasRole('LOGISTICIAN')` (+ `canEditJobOrder`), Job Order löschen `hasRole('ADMIN')`, Operation `hasRole('MISSION_MANAGER')`, SK-Lifecycle `hasRole('ADMIN')`, SK-Member-Verwaltung `@SpecialCommandSecurityService.canManageMembers(...)`.

## Mandanten-Scope-Modell

Der Scope wird **im Service-Layer** durchgesetzt (`OwnerScopeService`), nicht im Controller. Drei Aggregat-Scope-Arten:

- **Strict-Staffel** (kein staffel-übergreifender Zugriff): `Ship`, `InventoryItem` (direkte Lager-View), `RefineryOrder`, **`Operation`**. Listen filtern auf `owning_org_unit_id`; Detail-/Schreibendpunkte gaten über `canSee*`/`canEdit*`.
- **Cross-Staffel mit Public-Escape**: `Mission` (Einsatz). Für andere OrgUnits sichtbar, *wenn* `is_internal = false`; editierbar nur durch die besitzende OrgUnit + Admins. → UC-10.
- **Bedingt staffel-scoped (Sichtbarkeit über `responsibleOrgUnit.kind`, REQ-ORG-003)**: `JobOrder` + verknüpfte `JobOrderMaterial` + `JobOrderHandover`. Responsible = SK → **öffentlich** für alle profit-eligible Mitglieder (geteilte SK-Warteschlange); Responsible = Staffel → **privat** für diese Staffel + Admins. Vorgeschaltet ist das Profit-Gate (`canViewJobOrders`: Admin oder mindestens eine profit-eligible Mitgliedschaft). SK-Auftrags-*Edits* laufen über das Rollen-Gate (LOGISTICIAN+), nicht über den Staffel-Scope. Verknüpftes Inventar ist im Auftrags-Kontext cross-OrgUnit sichtbar (`findByJobOrderIdOrdered`, ungegated), leakt aber nie in eine fremde Lager-View. → UC-08, UC-09, UC-18.

> **Wichtig (Korrektur einer häufigen Annahme):** **Einsätze/Operationen und Refinery Orders sind strict-staffel, NICHT staffel-übergreifend.** Die staffel-übergreifende Zusammenarbeit läuft über **öffentliche Einsätze** (Teilnehmer aus anderen Staffeln, UC-10) und über den **Job-Order-Workspace** inkl. Handover (UC-08/UC-09) — nicht über Operationen oder Refinery Orders.

## Admin-Pin & Scope-Auflösung (`ScopePredicate`)

Listen-Endpunkte konsumieren ein Tupel `(boolean isAdminAllScope, UUID activeOrgUnitId, Set<UUID> memberOrgUnitIds)`:

- **Admin ohne Pin** → `isAdminAllScope = true` → sieht alles.
- **Admin mit Pin** → `activeOrgUnitId = <Pin>` → dieselbe restriktive Sicht wie ein Member dieser OrgUnit.
- **Non-Admin** → `memberOrgUnitIds = <Vereinigung aller Mitgliedschaften>`, kein Pin → sieht die Vereinigung seiner Staffel-/SK-Mitgliedschaften, sofern er nicht eine pinnt.

Beim Anlegen wird die OrgUnit zentral gestempelt (`resolveSquadronForPickerOutput` / `resolveOrgUnitForPickerOutput`, §5.5.1-Matrix): 0 Mitgliedschaften → 400; 1 + kein Picker-Output → Auto-Stamp; 1 + gültiger Picker → übernommen; 1 + fremder Picker → 400; >1 + kein Picker → 400 (explizite Wahl erzwingen); >1 + gültig → übernommen.

## SK (Spezialkommando) — Grundlagen

- SK und Staffel teilen die `org_unit`-Tabelle mit `kind`-Diskriminator (`SQUADRON` / `SPECIAL_COMMAND`). SK ist also eine vollwertige OrgUnit mit Mitgliedschaften.
- **SK-Lifecycle** (anlegen/umbenennen/löschen) ist ADMIN-only. **SK-Mitgliederverwaltung** ist offen für ADMIN oder den `is_lead`-Träger genau dieses SK (`canManageMembers`); der Lead-Toggle selbst bleibt ADMIN-only (kein Self-Escalation).
- **SK als besitzende OrgUnit von strict-Aggregaten ist möglich** (Inventar, Ship, Refinery Order, Mission, Operation): die Legacy-Spalte `owning_squadron_id` wurde in V102/V103 entfernt, `owning_org_unit_id` referenziert die polymorphe `org_unit`-Tabelle (und ist seit V132 für die personenbezogenen Aggregate nullable). Ein SK kann also Inventar besitzen — die `memberOrgUnitIds`-Vereinigung umfasst Staffel **und** SK. → UC-14. **Ausnahme Job Order:** ein nicht-profit-fähiges SK darf nicht die *bearbeitende* (responsible) Einheit eines Job Orders sein (400, Profit-Eligibility V128) → UC-11; als *anfragende* (requesting) Einheit ist jede OrgUnit zulässig.
- **SK können nicht am Beförderungssubsystem teilnehmen** (DB-CHECK + Trigger + JPA-Guards).

## Cross-Staffel-Mechanik beim Inventar (Kern von UC-08/UC-09)

Geteilte Repository-Methoden trennen die beiden Sichten sauber:

- `findGlobalByFilters(...)` — **gegated** (Scope-Prädikat): Staffel A sieht das Inventar von Staffel B **nicht** in ihrer Lager-View.
- `findByJobOrderIdOrdered(jobOrderId)` — **ungegated**: liefert **alle** an einen Job Order verknüpften Lagereinträge, egal welcher OrgUnit sie gehören.

So kann Staffel B ihr eigenes (B-besessenes) Inventar an einen Job Order von Staffel A verknüpfen; im Auftrags-Kontext ist es für A sichtbar (zur Erfüllung), es **leakt aber nie in A's Lager-View**.
