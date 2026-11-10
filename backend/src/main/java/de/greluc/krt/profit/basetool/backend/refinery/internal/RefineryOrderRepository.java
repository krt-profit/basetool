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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderCountsDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Refinery Order. */
@Repository
public interface RefineryOrderRepository extends JpaRepository<RefineryOrder, UUID> {

  /**
   * Counts every visible segment with the same owner or tenant scope as the order list.
   *
   * @param onlyMine whether the owner predicate replaces the tenant predicate
   * @param ownerId the calling member
   * @param activeStatuses statuses counted by the running and ready segments
   * @param completedStatus the completed segment's status
   * @param now readiness snapshot
   * @param isAdminAllScope whether every tenant is visible
   * @param activeOrgUnitId the active tenant pin, if any
   * @param memberOrgUnitIds the caller's allowed tenants
   * @return all four counts in one aggregate row
   */
  @Query(
      "SELECT new"
          + " de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderCountsDto(COALESCE(SUM(CASE"
          + " WHEN r.status IN :activeStatuses THEN 1L ELSE 0L END), 0L), COALESCE(SUM(CASE WHEN"
          + " r.status IN :activeStatuses AND (r.endsAt IS NULL OR r.endsAt <= :now) THEN 1L ELSE"
          + " 0L END), 0L), COALESCE(SUM(CASE WHEN r.status = :completedStatus THEN 1L ELSE 0L"
          + " END), 0L), COUNT(r)) FROM RefineryOrder r WHERE (:onlyMine = true AND r.owner.id ="
          + " :ownerId) OR (:onlyMine = false AND "
          + RefineryAccessPolicy.REFINERY_ORDER_SCOPE_TRIPLE
          + ")")
  RefineryOrderCountsDto countSegments(
      @Param("onlyMine") boolean onlyMine,
      @Param("ownerId") UUID ownerId,
      @Param("activeStatuses") Collection<RefineryOrderStatus> activeStatuses,
      @Param("completedStatus") RefineryOrderStatus completedStatus,
      @Param("now") Instant now,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * The list filters of the paged order lists (alias {@code r}, REQ-REFINERY-019): the statuses,
   * the ready filter and a case-insensitive substring search over the owner's display and user
   * name, the location, the refining method and the goods' input and output materials. The goods
   * are searched in a subquery, so an order with several matching goods is returned once.
   */
  String LIST_FILTER =
      "r.status IN :statuses"
          + " AND (:readyOnly = false OR r.endsAt IS NULL OR r.endsAt <= :now)"
          + " AND (:pattern IS NULL"
          + " OR LOWER(r.owner.username) LIKE :pattern ESCAPE '\\'"
          + " OR LOWER(r.owner.displayName) LIKE :pattern ESCAPE '\\'"
          + " OR LOWER(r.location.name) LIKE :pattern ESCAPE '\\'"
          + " OR EXISTS (SELECT rm.id FROM RefiningMethod rm WHERE rm.id = r.refiningMethod.id"
          + " AND LOWER(rm.name) LIKE :pattern ESCAPE '\\')"
          + " OR EXISTS (SELECT g.id FROM RefineryGood g LEFT JOIN g.inputMaterial gin"
          + " LEFT JOIN g.outputMaterial gout WHERE g.refineryOrder.id = r.id"
          + " AND (LOWER(gin.name) LIKE :pattern ESCAPE '\\'"
          + " OR LOWER(gout.name) LIKE :pattern ESCAPE '\\')))";

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
        "refiningMethod",
        "goods",
        "goods.inputMaterial",
        "goods.outputMaterial"
      })
  Optional<RefineryOrder> findById(@NotNull UUID id);

  /**
   * Loads one refinery order and row-locks it until the transaction ends, so a writer checks and
   * raises its section counters (REQ-REFINERY-029) on state no concurrent writer can change.
   *
   * @param id the order id
   * @return the locked order, or empty when none exists
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM RefineryOrder r WHERE r.id = :id")
  Optional<RefineryOrder> lockById(@Param("id") UUID id);

  /**
   * Loads one of a member's orders and row-locks it until the transaction ends; an order of another
   * member is neither returned nor locked (REQ-XCH-052).
   *
   * @param id the order id
   * @param owner the member
   * @return the locked order, or empty when the member owns none of that id
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM RefineryOrder r WHERE r.id = :id AND r.owner.id = :owner")
  Optional<RefineryOrder> lockOwnedById(@Param("id") UUID id, @Param("owner") UUID owner);

  /**
   * Row-locks a member's orders among the given ids in ascending id order, so two writers over
   * overlapping orders queue instead of deadlocking; an order of another member is neither returned
   * nor locked (REQ-XCH-052).
   *
   * @param owner the member
   * @param ids the order ids
   * @return the member's orders among them, locked for this transaction, ordered by id
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM RefineryOrder r WHERE r.owner.id = :owner AND r.id IN :ids ORDER BY r.id")
  List<RefineryOrder> lockOwnedByIdsOrderById(
      @Param("owner") UUID owner, @Param("ids") Collection<UUID> ids);

  /**
   * Counts refinery orders in the given lifecycle status, backing the {@code
   * basetool_refinery_order_open_*} queue-depth gauge (REQ-OBS-011).
   *
   * @param status the bounded lifecycle status to count
   * @return the number of refinery orders in that status
   */
  long countByStatus(RefineryOrderStatus status);

  /**
   * Counts a member's refinery orders, whatever their status (REQ-XCH-021).
   *
   * @param ownerId the owning member
   * @return the number of orders the member owns
   */
  long countByOwnerId(@NotNull UUID ownerId);

  /**
   * Lists which of the given orders exist, whoever owns them (REQ-XCH-052).
   *
   * @param ids the order ids
   * @return the ids among {@code ids} that name an order
   */
  @Query("SELECT r.id FROM RefineryOrder r WHERE r.id IN :ids")
  List<UUID> findExistingIds(@Param("ids") Collection<UUID> ids);

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
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  List<RefineryOrder> findByMissionId(UUID missionId);

  /**
   * Derived Spring-Data query - returns the orders linked to the mission without fetching any
   * association, for callers that only change the link.
   */
  List<RefineryOrder> findAllByMissionId(UUID missionId);

  /**
   * Derived Spring-Data query - returns entities matching {@code MissionIdAndOwnerId}. Eagerly
   * fetches the configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  List<RefineryOrder> findByMissionIdAndOwnerId(UUID missionId, UUID ownerId);

  /**
   * Org-unit-scoped variant of {@link #findByMissionId(UUID)}: returns only the mission's refinery
   * orders within the caller's scope triple (see {@link
   * de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate}).
   *
   * <p>Refinery is strict-staffel, so an order of a foreign org unit is never returned, even when
   * the mission itself is public.
   */
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  @Query(
      "SELECT r FROM RefineryOrder r WHERE r.missionId = :missionId AND "
          + RefineryAccessPolicy.REFINERY_ORDER_SCOPE_TRIPLE)
  List<RefineryOrder> findByMissionIdScoped(
      @Param("missionId") UUID missionId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Derived Spring-Data query - returns entities matching {@code MissionIdIn}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
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
      """
      select new de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryMissionProfitAggregate(
      r.missionId,
      sum(coalesce(r.oreSales, 0.0) - coalesce(r.expenses, 0.0)
      - coalesce(r.otherExpenses, 0.0)))
      from RefineryOrder r where r.missionId in :missionIds group by r.missionId
      """)
  List<RefineryMissionProfitAggregate> aggregateProfitByMissionIds(
      @Param("missionIds") List<UUID> missionIds);

  /**
   * Derived Spring-Data query - returns entities matching {@code OwnerId}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  List<RefineryOrder> findByOwnerId(UUID ownerId);

  /**
   * One page of the owner's refinery orders under the list filters of {@link #LIST_FILTER}, for the
   * own-orders list (REQ-REFINERY-019). Strictly owner-scoped, never org-unit-scoped.
   *
   * @param ownerId the owning user; never {@code null}
   * @param statuses the statuses to include; never empty
   * @param readyOnly {@code true} to keep only orders whose end is unknown or not after {@code now}
   * @param now the instant readiness is judged at; never {@code null}
   * @param pattern a lower-cased, LIKE-escaped {@code %fragment%}, or {@code null} for no search
   * @param pageable the page request; sortable by entity attributes, {@code endsAt} included
   * @return the matching page, each order once
   */
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  @Query("SELECT r FROM RefineryOrder r WHERE r.owner.id = :ownerId AND " + LIST_FILTER)
  Page<RefineryOrder> findOwnedFiltered(
      @Param("ownerId") UUID ownerId,
      @Param("statuses") Collection<RefineryOrderStatus> statuses,
      @Param("readyOnly") boolean readyOnly,
      @Param("now") Instant now,
      @Param("pattern") @Nullable String pattern,
      Pageable pageable);

  /**
   * One page of the refinery orders in the caller's scope triple (see {@link
   * de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate}) under the list filters of
   * {@link #LIST_FILTER}, for the scoped list (REQ-REFINERY-019).
   *
   * @param statuses the statuses to include; never empty
   * @param readyOnly {@code true} to keep only orders whose end is unknown or not after {@code now}
   * @param now the instant readiness is judged at; never {@code null}
   * @param pattern a lower-cased, LIKE-escaped {@code %fragment%}, or {@code null} for no search
   * @param isAdminAllScope {@code true} for an admin with no active pin (sees every order)
   * @param activeOrgUnitId the single pinned org-unit id, or {@code null} when unpinned
   * @param memberOrgUnitIds the caller's member org-unit ids (consulted only when {@code
   *     activeOrgUnitId} is {@code null})
   * @param pageable the page request; sortable by entity attributes, {@code endsAt} included
   * @return the matching in-scope page, each order once
   */
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  @Query(
      "SELECT r FROM RefineryOrder r WHERE "
          + LIST_FILTER
          + " AND "
          + RefineryAccessPolicy.REFINERY_ORDER_SCOPE_TRIPLE)
  Page<RefineryOrder> findFilteredScoped(
      @Param("statuses") Collection<RefineryOrderStatus> statuses,
      @Param("readyOnly") boolean readyOnly,
      @Param("now") Instant now,
      @Param("pattern") @Nullable String pattern,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Org-unit-scoped variant of {@link #findByOwnerId(UUID)} for the cross-user oversight endpoint:
   * returns only the target user's refinery orders whose {@code owningOrgUnit} falls within the
   * caller's scope (see {@link de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate}).
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
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  @Query(
      "SELECT r FROM RefineryOrder r WHERE r.owner.id = :ownerId AND "
          + RefineryAccessPolicy.REFINERY_ORDER_SCOPE_TRIPLE)
  Page<RefineryOrder> findByOwnerIdScoped(
      @Param("ownerId") UUID ownerId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
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
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  Page<RefineryOrder> findByStatusIn(List<RefineryOrderStatus> statuses, Pageable pageable);

  /**
   * Lists every entity. Overridden here to attach an {@code @EntityGraph}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @Override
  @EntityGraph(attributePaths = {"owner", "location", "refiningMethod", "owningOrgUnit"})
  Page<RefineryOrder> findAll(Pageable pageable);

  /**
   * Bulk-clears the mission link on every refinery order tied to one of the given missions so the
   * orders survive a mission delete as unassigned.
   */
  @Modifying
  @Query("UPDATE RefineryOrder r SET r.missionId = null WHERE r.missionId IN :missionIds")
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

  /**
   * Returns the ids of the open orders whose run ended in {@code (notBefore, now]} and that have
   * not been announced yet (REQ-REFINERY-023).
   *
   * @param notBefore the earliest run end that is still announced, exclusive
   * @param now the instant the run ends are compared with, inclusive
   * @param limit the most ids to return
   * @return the ids, oldest run end first; never {@code null}
   */
  @Query(
      """
      SELECT o.id FROM RefineryOrder o
      WHERE o.status IN (de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus.OPEN,
                         de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderStatus.IN_PROGRESS)
        AND o.storedAt IS NULL AND o.readyNotifiedAt IS NULL
        AND o.endsAt IS NOT NULL AND o.endsAt > :notBefore AND o.endsAt <= :now
      ORDER BY o.endsAt, o.id
      """)
  List<UUID> findReadyUnannouncedIds(
      @Param("notBefore") Instant notBefore, @Param("now") Instant now, Limit limit);

  /**
   * Marks an order as announced, atomically, without touching its version: the one caller whose
   * update changes a row is the one that announces it.
   *
   * @param id the order
   * @param now the announcement time
   * @return {@code 1} when this call marked the order, {@code 0} when it was marked already
   */
  @Modifying
  @Query(
      "UPDATE RefineryOrder o SET o.readyNotifiedAt = :now"
          + " WHERE o.id = :id AND o.readyNotifiedAt IS NULL")
  int markReadyNotified(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Returns the ids of one exchange snapshot page of a member's orders in the given statuses.
   *
   * @param owner the owning member
   * @param statuses the statuses to include
   * @param after the last order delivered, or the zero id for the first page
   * @param limit the page size
   * @return the ids after {@code after}, ascending
   */
  @Query(
      "SELECT o.id FROM RefineryOrder o WHERE o.owner.id = :owner AND o.status IN :statuses"
          + " AND o.id > :after ORDER BY o.id")
  List<UUID> findExchangeOrderIds(
      @Param("owner") UUID owner,
      @Param("statuses") Collection<RefineryOrderStatus> statuses,
      @Param("after") UUID after,
      Limit limit);

  /**
   * Loads a member's orders with everything the exchange feed serves: the location with its city
   * and space station, the refining method and the goods with their materials.
   *
   * @param owner the owning member
   * @param ids the order ids
   * @return the orders among {@code ids} the member owns
   */
  @EntityGraph(
      attributePaths = {
        "location",
        "location.city",
        "location.spaceStation",
        "refiningMethod",
        "goods",
        "goods.inputMaterial",
        "goods.outputMaterial"
      })
  @Query("SELECT o FROM RefineryOrder o WHERE o.owner.id = :owner AND o.id IN :ids")
  List<RefineryOrder> findExchangeOrders(
      @Param("owner") UUID owner, @Param("ids") Collection<UUID> ids);

  /**
   * Inserts a good under an id it had before, which the undo of an exchange write brings back
   * (REQ-XCH-056); the generated id of a JPA insert cannot carry it.
   *
   * @param id the good's former row id
   * @param order the order
   * @param input the input material
   * @param inputQuantity the input amount in units
   * @param output the output material
   * @param outputQuantity the yield in units
   * @param quality the quality from 0 to 1000
   */
  @Modifying(flushAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO refinery_good (id, version, created_at, updated_at, input_material_id,
                                     input_quantity, output_material_id, output_quantity, quality,
                                     refinery_order_id)
          VALUES (:id, 0, now(), now(), :input, :inputQuantity, :output, :outputQuantity, :quality,
                  :order)
          """)
  void insertGood(
      @Param("id") UUID id,
      @Param("order") UUID order,
      @Param("input") UUID input,
      @Param("inputQuantity") int inputQuantity,
      @Param("output") UUID output,
      @Param("outputQuantity") int outputQuantity,
      @Param("quality") int quality);

  /**
   * Inserts an order under the id it had before, unstored, with the member's undo as its last
   * change, for the undo of a client's delete (REQ-XCH-056); the generated id of a JPA insert
   * cannot carry it.
   *
   * @param row the order's columns
   */
  @Modifying(flushAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO refinery_order (id, version, created_at, updated_at, owner_id, location_id,
                                      mission_id, started_at, duration_minutes,
                                      refining_method_id, expenses, other_expenses, ore_sales,
                                      status, ready_notified_at, owning_org_unit_id,
                                      game_data_version, finances_version, created_source,
                                      created_client, last_change_source, last_changed_at,
                                      picked_up_at, picked_up_client)
          VALUES (:#{#row.id()}, 0,
                  COALESCE(CAST(:#{#row.createdAt()} AS timestamptz), now()), now(),
                  :#{#row.owner()}, :#{#row.location()}, CAST(:#{#row.mission()} AS uuid),
                  CAST(:#{#row.startedAt()} AS timestamptz),
                  CAST(:#{#row.durationMinutes()} AS bigint), CAST(:#{#row.method()} AS uuid),
                  CAST(:#{#row.expenses()} AS double precision),
                  CAST(:#{#row.otherExpenses()} AS double precision),
                  CAST(:#{#row.oreSales()} AS double precision), :#{#row.status()},
                  CAST(:#{#row.readyNotifiedAt()} AS timestamptz),
                  CAST(:#{#row.orgUnit()} AS uuid),
                  :#{#row.gameDataVersion()}, :#{#row.financesVersion()},
                  CASE WHEN CAST(:#{#row.createdClient()} AS varchar) IS NULL THEN 'WEB'
                       ELSE 'CLIENT' END,
                  CAST(:#{#row.createdClient()} AS varchar), 'WEB', now(),
                  CAST(:#{#row.pickedUpAt()} AS timestamptz),
                  CAST(:#{#row.pickedUpClient()} AS varchar))
          """)
  void insertOrder(@Param("row") RestoredRow row);

  /**
   * The columns of an order the undo of a client's delete inserts again.
   *
   * @param id the former id
   * @param createdAt the creation time, or {@code null} for now
   * @param owner the owning member
   * @param location the location
   * @param mission the linked mission, or {@code null}
   * @param startedAt the run's start, or {@code null}
   * @param durationMinutes the run's length, or {@code null}
   * @param method the refining method, or {@code null}
   * @param expenses the refining costs, or {@code null}
   * @param otherExpenses further costs, or {@code null}
   * @param oreSales the raw-ore revenue, or {@code null}
   * @param status the status name
   * @param readyNotifiedAt when the ready notice was raised, or {@code null}
   * @param orgUnit the owning unit, or {@code null}
   * @param gameDataVersion the game-data counter
   * @param financesVersion the finances counter
   * @param createdClient the creating client, or {@code null} for the web
   * @param pickedUpAt when a client reported the pickup, or {@code null}
   * @param pickedUpClient that client, or {@code null}
   */
  record RestoredRow(
      @NotNull UUID id,
      @Nullable Instant createdAt,
      @NotNull UUID owner,
      @NotNull UUID location,
      @Nullable UUID mission,
      @Nullable Instant startedAt,
      @Nullable Long durationMinutes,
      @Nullable UUID method,
      @Nullable Double expenses,
      @Nullable Double otherExpenses,
      @Nullable Double oreSales,
      @NotNull String status,
      @Nullable Instant readyNotifiedAt,
      @Nullable UUID orgUnit,
      long gameDataVersion,
      long financesVersion,
      @Nullable String createdClient,
      @Nullable Instant pickedUpAt,
      @Nullable String pickedUpClient) {}
}
