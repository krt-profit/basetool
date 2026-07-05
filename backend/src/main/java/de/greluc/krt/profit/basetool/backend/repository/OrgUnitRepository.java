/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository over the polymorphic {@link OrgUnit} base entity. Unlike {@link
 * SquadronRepository} and {@link SpecialCommandRepository} — each narrowed to one discriminator
 * subtype — this repository loads {@code org_unit} rows of <em>either</em> kind through Hibernate's
 * single-table inheritance, returning the matching concrete subclass ({@code Squadron} or {@code
 * SpecialCommand}) per row.
 *
 * <p>Used by {@code MissionService} when stamping a participant's affiliations: a participant may
 * be linked to a Staffel and one or more Spezialkommandos at once, so the service resolves the
 * caller's membership org-unit ids to managed {@link OrgUnit} entities here rather than branching
 * on kind and dispatching to the two kind-specific repositories.
 */
@Repository
public interface OrgUnitRepository extends JpaRepository<OrgUnit, UUID> {

  /**
   * Counts how many of the given org-unit ids are flagged {@code is_profit_eligible} — works across
   * both kinds (Squadron + SpecialCommand) via single-table inheritance. Drives {@code
   * OwnerScopeService.canViewJobOrders()}: a caller may enter the Job-Order area iff at least one
   * of their membership org units is profit-eligible, so the service only needs the {@code > 0}
   * answer. Callers must pass a non-empty collection — an empty {@code IN ()} renders
   * inconsistently across dialects, so {@code OwnerScopeService} short-circuits the empty case
   * before calling this.
   *
   * @param ids the org-unit ids to inspect (the caller's membership ids); must be non-empty.
   * @return the number of those ids whose org unit is profit-eligible; {@code 0} when none.
   */
  @Query("SELECT COUNT(o) FROM OrgUnit o WHERE o.id IN :ids AND o.isProfitEligible = true")
  long countProfitEligibleByIdIn(@Param("ids") Collection<UUID> ids);

  /**
   * Loads every active leaf org unit — i.e. every {@link
   * de.greluc.krt.profit.basetool.backend.model.Squadron Squadron} and {@link
   * de.greluc.krt.profit.basetool.backend.model.SpecialCommand SpecialCommand} — across both kinds
   * via single-table inheritance, <strong>regardless of {@code is_profit_eligible}</strong>. Backs
   * the organisation-wide org chart (ADR-0029, REQ-ORG-018): the chart's unit tier is every active
   * Staffel + SK so that a Staffel/SK an admin has wired under any Bereich renders there, not only
   * the Profit-side ones. {@code is_profit_eligible} governs Job-Order processing only (the {@code
   * countProfitEligibleByIdIn} path) and must not gate chart visibility. The caller splits the
   * result by {@link OrgUnit#getKind()} into the squadron and SK columns.
   *
   * @return the active Staffeln + SKs in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o FROM OrgUnit o WHERE o.active = true AND TYPE(o) IN (Squadron, SpecialCommand)")
  List<OrgUnit> findActiveSquadronsAndSpecialCommands();

  /**
   * Returns the direct children of {@code parentOrgUnitId} in the org hierarchy (epic #692,
   * REQ-ORG-014): the Staffeln + SKs of a Bereich, or the Bereiche of the Organisationsleitung.
   * Returns every kind via single-table inheritance; the caller filters by {@link
   * OrgUnit#getKind()} if it needs a specific tier. The cascading-scope resolver (REQ-ORG-015)
   * walks this one level at a time to expand a leader's reach to their subordinate units.
   *
   * @param parentOrgUnitId the parent org unit whose direct children to load; never {@code null}.
   * @return the direct children in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o FROM OrgUnit o WHERE o.parent.id = :parentOrgUnitId")
  List<OrgUnit> findByParentOrgUnitId(@Param("parentOrgUnitId") UUID parentOrgUnitId);

  /**
   * Id-only projection of {@link #findByParentOrgUnitId(UUID)}: the ids of the direct children of
   * {@code parentOrgUnitId} (the Staffeln + SKs of a Bereich). Used by the cascading-scope resolver
   * ({@link de.greluc.krt.profit.basetool.backend.service.OrgUnitCascadeService}, REQ-ORG-015) to
   * expand a Bereichsleitung member's reach to their subordinate units without hydrating the full
   * {@link OrgUnit} rows. The fixed three-level hierarchy (OL &gt; Bereich &gt; Staffel/SK) means a
   * Bereich's children are exactly the leaf units, so one call yields the whole subtree below a
   * Bereich.
   *
   * @param parentOrgUnitId the parent org unit whose direct child ids to load; never {@code null}.
   * @return the direct child org-unit ids in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o.id FROM OrgUnit o WHERE o.parent.id = :parentOrgUnitId")
  List<UUID> findChildOrgUnitIds(@Param("parentOrgUnitId") UUID parentOrgUnitId);

  /**
   * Returns the id of every org unit across all kinds (Squadron, SK, Bereich, OL) via single-table
   * inheritance. Backs the Organisationsleitung branch of the cascading-scope resolver ({@link
   * de.greluc.krt.profit.basetool.backend.service.OrgUnitCascadeService}, REQ-ORG-015): an OL
   * member's reach is the concrete union of <em>every</em> org-unit id — deliberately materialised
   * rather than collapsed into an admin-all marker, so OL/Bereich leadership never inherits the
   * admin carve-outs (the HARD INVARIANT of REQ-ORG-015). Including units with a {@code null}
   * parent (the additive-soak window before the hierarchy is wired up) is intentional: OL reach is
   * "everything", not "everything reachable through a parent edge".
   *
   * @return every org-unit id in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o.id FROM OrgUnit o")
  List<UUID> findAllOrgUnitIds();

  /**
   * Loads every active {@link de.greluc.krt.profit.basetool.backend.model.Bereich} (epic #692,
   * REQ-ORG-018). Backs the multi-Bereich org chart's tier list: each Bereich renders as its own
   * leadership sub-tree, coloured by its {@link
   * de.greluc.krt.profit.basetool.backend.model.Department Department}, with its child Staffeln/SKs
   * grouped underneath. Returns the {@code BEREICH} discriminator only via the typed JPQL {@code
   * FROM Bereich}.
   *
   * @return the active Bereiche in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o FROM Bereich o WHERE o.active = true")
  List<OrgUnit> findActiveBereiche();

  /**
   * Loads the active {@link de.greluc.krt.profit.basetool.backend.model.Organisationsleitung} (epic
   * #692, REQ-ORG-018) — normally a singleton. Backs the OL root tier of the org chart.
   *
   * @return the active OL row(s) in arbitrary order; never {@code null}, normally one or zero.
   */
  @Query("SELECT o FROM Organisationsleitung o WHERE o.active = true")
  List<OrgUnit> findActiveOrganisationsleitung();

  /**
   * Loads every active org unit across all four kinds (Squadron, SK, Bereich, OL) with its parent
   * eagerly fetched, for the admin hierarchy-management surface (epic #692, REQ-ORG-014). Backs the
   * one read the management page needs: each row carries its current {@code parent_org_unit_id} (to
   * show where it sits) and its optimistic-lock {@code version} (to PATCH a new parent edge), so
   * the whole table — and the per-kind parent-option pools — comes from a single call. The {@code
   * LEFT JOIN FETCH o.parent} initialises the parent in the same query, keeping any parent access
   * in the mapping layer single-query rather than lazily per row.
   *
   * @return the active org units (parent pre-loaded) in arbitrary order; never {@code null},
   *     possibly empty.
   */
  @Query("SELECT o FROM OrgUnit o LEFT JOIN FETCH o.parent WHERE o.active = true")
  List<OrgUnit> findAllActiveWithParent();

  /**
   * Loads the given org units by id with their parent eagerly fetched — the bank dashboard's
   * owner-label read for the by-Bereich grouping (REQ-BANK-016): an {@code AREA} account's owning
   * org unit is the Bereich itself, a Staffel/SK account's Bereich is that owner's parent, so the
   * caller needs both the owner and its parent. The {@code LEFT JOIN FETCH o.parent} keeps the
   * resolution a single query instead of one lazy load per account, so the dashboard stays N+1-free
   * (REQ-DATA-003). Includes inactive org units so a deactivated Staffel's account still groups
   * under its Bereich. This is a plain owner-label read, not an org-unit scope decision
   * (REQ-BANK-008). An empty collection returns an empty list.
   *
   * @param ids the owning org-unit ids to load (parent pre-loaded)
   * @return the matching org units with their parent initialised, in arbitrary order
   */
  @Query("SELECT o FROM OrgUnit o LEFT JOIN FETCH o.parent WHERE o.id IN :ids")
  List<OrgUnit> findAllByIdInWithParent(@Param("ids") Collection<UUID> ids);
}
