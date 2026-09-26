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
 * Spring Data repository over the polymorphic {@link OrgUnit} base entity, loading rows of any kind
 * as their concrete subclass.
 */
@Repository
public interface OrgUnitRepository extends JpaRepository<OrgUnit, UUID> {

  /**
   * Counts how many of the given org units are flagged {@code is_profit_eligible}; drives {@code
   * OwnerScopeService.canViewJobOrders()}.
   *
   * @param ids the org-unit ids to inspect (the caller's membership ids); must be non-empty.
   * @return the number of those ids whose org unit is profit-eligible; {@code 0} when none.
   */
  @Query("SELECT COUNT(o) FROM OrgUnit o WHERE o.id IN :ids AND o.isProfitEligible = true")
  long countProfitEligibleByIdIn(@Param("ids") Collection<UUID> ids);

  /**
   * Loads every active {@link de.greluc.krt.profit.basetool.backend.model.Squadron Squadron} and
   * {@link de.greluc.krt.profit.basetool.backend.model.SpecialCommand SpecialCommand}, regardless
   * of {@code is_profit_eligible}, for the org chart (REQ-ORG-026).
   *
   * @return the active Staffeln + SKs in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o FROM OrgUnit o WHERE o.active = true AND TYPE(o) IN (Squadron, SpecialCommand)")
  List<OrgUnit> findActiveSquadronsAndSpecialCommands();

  /**
   * Returns the direct children of {@code parentOrgUnitId} of every kind (REQ-ORG-014).
   *
   * @param parentOrgUnitId the parent org unit whose direct children to load; never {@code null}.
   * @return the direct children in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o FROM OrgUnit o WHERE o.parent.id = :parentOrgUnitId")
  List<OrgUnit> findByParentOrgUnitId(@Param("parentOrgUnitId") UUID parentOrgUnitId);

  /**
   * Id-only projection of {@link #findByParentOrgUnitId(UUID)}, used by {@link
   * de.greluc.krt.profit.basetool.backend.service.OrgUnitCascadeService} to expand a Bereich to its
   * units (REQ-ORG-015).
   *
   * @param parentOrgUnitId the parent org unit whose direct child ids to load; never {@code null}.
   * @return the direct child org-unit ids in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o.id FROM OrgUnit o WHERE o.parent.id = :parentOrgUnitId")
  List<UUID> findChildOrgUnitIds(@Param("parentOrgUnitId") UUID parentOrgUnitId);

  /**
   * Returns the id of every org unit of every kind, including parentless ones; the
   * Organisationsleitung reach in {@link
   * de.greluc.krt.profit.basetool.backend.service.OrgUnitCascadeService} (REQ-ORG-015).
   *
   * <p>Materialised as concrete ids, never as an admin-all marker, so OL reach never inherits the
   * admin carve-outs.
   *
   * @return every org-unit id in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o.id FROM OrgUnit o")
  List<UUID> findAllOrgUnitIds();

  /**
   * Loads every active {@link de.greluc.krt.profit.basetool.backend.model.Bereich} for the org
   * chart's Bereich tiers (REQ-ORG-026).
   *
   * @return the active Bereiche in arbitrary order; never {@code null}, possibly empty.
   */
  @Query("SELECT o FROM Bereich o WHERE o.active = true")
  List<OrgUnit> findActiveBereiche();

  /**
   * Loads the active {@link de.greluc.krt.profit.basetool.backend.model.Organisationsleitung},
   * normally a singleton, for the org chart's root tier (REQ-ORG-026).
   *
   * @return the active OL row(s) in arbitrary order; never {@code null}, normally one or zero.
   */
  @Query("SELECT o FROM Organisationsleitung o WHERE o.active = true")
  List<OrgUnit> findActiveOrganisationsleitung();

  /**
   * Loads every active org unit of every kind with its parent fetched in the same query, for the
   * admin hierarchy-management page (REQ-ORG-014).
   *
   * @return the active org units (parent pre-loaded) in arbitrary order; never {@code null},
   *     possibly empty.
   */
  @Query("SELECT o FROM OrgUnit o LEFT JOIN FETCH o.parent WHERE o.active = true")
  List<OrgUnit> findAllActiveWithParent();

  /**
   * Loads the given org units, active or not, with their parent fetched in the same query; the bank
   * dashboard's owner-label read for the by-Bereich grouping (REQ-BANK-016).
   *
   * <p>An empty collection returns an empty list.
   *
   * @param ids the owning org-unit ids to load (parent pre-loaded)
   * @return the matching org units with their parent initialised, in arbitrary order
   */
  @Query("SELECT o FROM OrgUnit o LEFT JOIN FETCH o.parent WHERE o.id IN :ids")
  List<OrgUnit> findAllByIdInWithParent(@Param("ids") Collection<UUID> ids);
}
