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

import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Refinery Order. */
@Repository
public interface RefineryOrderRepository extends JpaRepository<RefineryOrder, UUID> {

  /**
   * Loads one refinery order with everything its detail DTO reads: the to-one associations and the
   * goods with their input and output materials.
   *
   * @param id the order id
   * @return the order with its detail graph loaded, or empty when none exists
   */
  @Override
  @NotNull
  @EntityGraph(
      attributePaths = {
        "owner",
        "location",
        "mission",
        "refiningMethod",
        "goods",
        "goods.inputMaterial",
        "goods.outputMaterial"
      })
  Optional<RefineryOrder> findById(@NotNull UUID id);

  /**
   * Counts refinery orders in the given lifecycle status, backing the {@code
   * basetool_refinery_order_open_*} queue-depth gauge (REQ-OBS-011).
   *
   * @param status the bounded lifecycle status to count
   * @return the number of refinery orders in that status
   */
  long countByStatus(RefineryOrderStatus status);

  /**
   * Finds the creation timestamp of the oldest refinery order in the given status, for the "oldest
   * open refinery order age" gauge (REQ-OBS-011).
   *
   * @param status the bounded lifecycle status to scan (typically {@code OPEN})
   * @return the earliest {@code createdAt} in that status, or {@code null} when none exists
   */
  @Query("SELECT MIN(r.createdAt) FROM RefineryOrder r WHERE r.status = :status")
  Instant findOldestCreatedAtByStatus(@Param("status") RefineryOrderStatus status);

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * LocationId}.
   */
  boolean existsByLocationId(UUID locationId);

  /**
   * Derived Spring-Data query - returns entities matching {@code MissionId}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  List<RefineryOrder> findByMissionId(UUID missionId);

  /**
   * Derived Spring-Data query - returns entities matching {@code MissionIdAndOwnerId}. Eagerly
   * fetches the configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  List<RefineryOrder> findByMissionIdAndOwnerId(UUID missionId, UUID ownerId);

  /**
   * Org-unit-scoped variant of {@link #findByMissionId(UUID)}: returns only the mission's refinery
   * orders within the caller's scope triple (see {@link
   * de.greluc.krt.profit.basetool.backend.service.ScopePredicate}).
   *
   * <p>Refinery is strict-staffel, so an order of a foreign org unit is never returned, even when
   * the mission itself is public.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  @Query(
      "SELECT r FROM RefineryOrder r WHERE r.mission.id = :missionId AND "
          + ScopeSpecifications.REFINERY_ORDER_SCOPE_TRIPLE)
  List<RefineryOrder> findByMissionIdScoped(
      @Param("missionId") UUID missionId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Derived Spring-Data query - returns entities matching {@code MissionIdIn}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  List<RefineryOrder> findByMissionIdIn(List<UUID> missionIds);

  /**
   * Aggregates the refinery profit/loss of several missions into one {@code (missionId, profitSum)}
   * row per mission in a single grouped query. Each order contributes {@code oreSales − expenses −
   * otherExpenses} (null fields as 0); a mission without refinery orders yields no row.
   *
   * @param missionIds the missions to aggregate, typically an operation's child missions
   * @return one aggregate per mission with at least one refinery order; the sum may be {@code null}
   */
  @Query(
      "select new de.greluc.krt.profit.basetool.backend.repository.RefineryMissionProfitAggregate("
          + "r.mission.id,"
          + " sum(coalesce(r.oreSales, 0.0) - coalesce(r.expenses, 0.0)"
          + " - coalesce(r.otherExpenses, 0.0)))"
          + " from RefineryOrder r where r.mission.id in :missionIds group by r.mission.id")
  List<RefineryMissionProfitAggregate> aggregateProfitByMissionIds(
      @Param("missionIds") List<UUID> missionIds);

  /**
   * Derived Spring-Data query - returns entities matching {@code OwnerId}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  List<RefineryOrder> findByOwnerId(UUID ownerId);

  /**
   * Derived Spring-Data query - returns entities matching {@code OwnerId}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  Page<RefineryOrder> findByOwnerId(UUID ownerId, Pageable pageable);

  /**
   * Derived Spring-Data query - returns entities matching {@code OwnerIdAndStatusIn}. Eagerly
   * fetches the configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  Page<RefineryOrder> findByOwnerIdAndStatusIn(
      UUID ownerId, List<RefineryOrderStatus> statuses, Pageable pageable);

  /**
   * Org-unit-scoped variant of {@link #findByOwnerId(UUID, Pageable)} for the cross-user oversight
   * endpoint: returns only the target user's refinery orders whose {@code owningOrgUnit} falls
   * within the caller's scope (see {@link
   * de.greluc.krt.profit.basetool.backend.service.ScopePredicate}).
   *
   * <p>Refinery is strict-staffel, so the list never returns an order the per-order {@code
   * canSeeRefineryOrder} gate would deny.
   *
   * @param ownerId the target user whose orders to list; never {@code null}
   * @param isAdminAllScope {@code true} for an admin with no active pin (sees every order)
   * @param activeOrgUnitId the single pinned org-unit id, or {@code null} when unpinned
   * @param memberOrgUnitIds the caller's member org-unit ids (consulted only when {@code
   *     activeOrgUnitId} is {@code null})
   * @param pageable the page request
   * @return the in-scope page of the target user's refinery orders
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  @Query(
      "SELECT r FROM RefineryOrder r WHERE r.owner.id = :ownerId AND "
          + ScopeSpecifications.REFINERY_ORDER_SCOPE_TRIPLE)
  Page<RefineryOrder> findByOwnerIdScoped(
      @Param("ownerId") UUID ownerId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Loads the caller's own refinery orders in the given statuses with their goods and each good's
   * {@code outputMaterial}, for the blueprint craftability calculation. Strictly owner-scoped,
   * never org-unit-scoped.
   *
   * @param ownerId the owning user; never {@code null}
   * @param statuses the statuses to include (typically {@code OPEN}, {@code IN_PROGRESS})
   * @return the matching orders with goods loaded; never {@code null}, possibly empty
   */
  @EntityGraph(attributePaths = {"goods", "goods.outputMaterial"})
  @Query(
      """
      SELECT DISTINCT r FROM RefineryOrder r WHERE r.owner.id = :ownerId AND r.status IN
      :statuses
      """)
  List<RefineryOrder> findOwnedWithGoodsByStatusIn(
      @Param("ownerId") UUID ownerId, @Param("statuses") Collection<RefineryOrderStatus> statuses);

  /**
   * Derived Spring-Data query - returns entities matching {@code StatusIn}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  Page<RefineryOrder> findByStatusIn(List<RefineryOrderStatus> statuses, Pageable pageable);

  /**
   * Lists every entity. Overridden here to attach an {@code @EntityGraph}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @Override
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  Page<RefineryOrder> findAll(Pageable pageable);

  /**
   * Multi-tenant variant of {@link #findAll(Pageable)}: returns every refinery order whose owning
   * squadron matches {@code owningSquadronId}, or every order when {@code owningSquadronId} is
   * {@code null} (admin "all squadrons" mode). Refinery is a strict-staffel aggregate - there is no
   * cross-squadron escape clause like Mission's {@code is_internal = false}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  @Query("SELECT r FROM RefineryOrder r WHERE " + ScopeSpecifications.REFINERY_ORDER_SCOPE_TRIPLE)
  Page<RefineryOrder> findAllScoped(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Scoped variant of {@link #findByStatusIn(List, Pageable)}: filters by org-unit scope using the
   * standard {@code isAdminAllScope} / {@code activeOrgUnitId} / {@code memberOrgUnitIds} triple.
   * Used by the refinery list page when an admin selected an active squadron via the switcher and
   * by non-admin users to see the union of their org-unit refinery orders.
   */
  @EntityGraph(attributePaths = {"owner", "location", "mission", "refiningMethod", "owningOrgUnit"})
  @Query(
      "SELECT r FROM RefineryOrder r WHERE r.status IN :statuses AND "
          + ScopeSpecifications.REFINERY_ORDER_SCOPE_TRIPLE)
  Page<RefineryOrder> findByStatusInScoped(
      @Param("statuses") List<RefineryOrderStatus> statuses,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Bulk-clears the {@code mission} reference on every refinery order tied to one of the given
   * missions so the orders survive a mission delete as unassigned (see CLAUDE.md "Operation delete
   * keeps missions intact" rule for the broader pattern).
   */
  @Modifying
  @Query("UPDATE RefineryOrder r SET r.mission = null WHERE r.mission.id IN :missionIds")
  void unlinkMissions(@Param("missionIds") List<UUID> missionIds);

  /**
   * Bulk-reassigns every refinery order owned by {@code oldUser} to {@code newUser}; used by the
   * user-deletion cascade so refinery history is preserved when an account is removed.
   *
   * @param oldUser the previous owner
   * @param newUser the new owner (the fallback admin)
   * @return the number of refinery orders reassigned
   */
  @Modifying
  @Query("UPDATE RefineryOrder r SET r.owner = :newUser WHERE r.owner = :oldUser")
  int updateOwner(@NotNull User oldUser, @NotNull User newUser);
}
