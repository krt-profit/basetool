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

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Ship. */
@Repository
public interface ShipRepository extends JpaRepository<Ship, UUID> {

  /**
   * Flips the {@code fitted} flag back to {@code false} on every ship; used by the fleet-import
   * flow as the first step before re-applying the freshly imported fitted set. {@code
   * clearAutomatically = true} flushes the persistence context so subsequent saves in the same
   * transaction do not collide with stale {@code @Version} state.
   */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Ship s SET s.fitted = false")
  void resetAllFitted();

  /**
   * OrgUnit-scoped variant of {@link #resetAllFitted()}. Used by the admin/officer "reset fitted"
   * action so a focused-mode caller only wipes the {@code fitted} flag on ships of their own
   * OrgUnit (MULTI_SQUADRON_PLAN.md section 1: Hangar = strict eigene Staffel). Uses the R6.c
   * scope-predicate triple: admin all-scope resets every ship; pinned active OrgUnit resets only
   * that one; non-admin path resets the union of memberships.
   *
   * @param isAdminAllScope {@code true} iff the caller is admin without an active selection
   * @param activeOrgUnitId pinned OrgUnit id, or {@code null}
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin path)
   */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Ship s SET s.fitted = false WHERE " + ScopeSpecifications.SHIP_SCOPE_TRIPLE)
  void resetAllFittedScoped(
      @org.springframework.data.repository.query.Param("isAdminAllScope") boolean isAdminAllScope,
      @org.springframework.data.repository.query.Param("activeOrgUnitId") UUID activeOrgUnitId,
      @org.springframework.data.repository.query.Param("memberOrgUnitIds")
          java.util.Collection<UUID> memberOrgUnitIds);

  /**
   * Bulk-sets the location of every ship owned by {@code ownerId} to {@code location}; backs the
   * hangar "set home location" action. A single atomic JPQL update — it does NOT touch the
   * {@code @Version} column, so it never triggers an optimistic-lock storm; the caller reloads the
   * page afterwards to resync the displayed location. {@code clearAutomatically = true} flushes the
   * persistence context so any later read in the same transaction sees the new state. The {@code
   * owner.id} predicate enforces per-user isolation — never touches another user's ships.
   *
   * @param ownerId the owning user id whose ships are updated
   * @param location the location to set on every matching ship
   * @return the number of ships updated
   */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Ship s SET s.location = :location WHERE s.owner.id = :ownerId")
  int setLocationForOwner(
      @org.springframework.data.repository.query.Param("ownerId") UUID ownerId,
      @org.springframework.data.repository.query.Param("location") Location location);

  /**
   * Deletes the whole hangar of the given owner as part of the hard account deletion
   * (REQ-DATA-008). A ship is purely personal property — it carries no squadron-shared state — so a
   * departing member's fleet is removed rather than reassigned to a fallback admin, which used to
   * leave admins owning dozens of ex-members' ships.
   *
   * <p>Set-based on purpose: {@code Ship.owner} is a non-optional {@code @ManyToOne}, so loading
   * the ships as managed entities before the owning {@code app_user} row is removed would abort the
   * flush with {@code TransientPropertyValueException}. Keep this a bulk {@code DELETE}.
   *
   * @param ownerId the owner whose ships are removed; never {@code null}.
   * @return the number of deleted ships, for the audit summary event.
   */
  @Modifying
  @Query("DELETE FROM Ship s WHERE s.owner.id = :ownerId")
  int deleteByOwnerId(@org.springframework.data.repository.query.Param("ownerId") UUID ownerId);

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * ShipTypeId}.
   */
  boolean existsByShipTypeId(UUID shipTypeId);

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * LocationId}.
   */
  boolean existsByLocationId(UUID locationId);

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * OwnerIdAndShipTypeId}.
   */
  boolean existsByOwnerIdAndShipTypeId(UUID ownerId, UUID shipTypeId);

  /**
   * Derived Spring-Data query - returns the count of rows matching {@code OwnerIdAndShipTypeId}.
   */
  long countByOwnerIdAndShipTypeId(UUID ownerId, UUID shipTypeId);

  /**
   * Derived Spring-Data query - returns entities matching {@code OwnerId}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"shipType", "location", "owner", "owningOrgUnit"})
  List<Ship> findByOwnerId(UUID ownerId);

  /**
   * Derived Spring-Data query - returns entities matching {@code OwnerId}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"shipType", "location", "owner", "owningOrgUnit"})
  Page<Ship> findByOwnerId(UUID ownerId, Pageable pageable);

  /**
   * One page of the calling user's own ships, server-side ordered by the rich multi-key comparator
   * the personal hangar relies on and optionally narrowed by a case-insensitive search term
   * (REQ-HANGAR-002). This replaces the former {@code size=1000} fetch-all + client-side {@code
   * SHIP_SORT}: the ordering and the filter now span <em>all</em> the user's ships, not just the
   * rows of the current page.
   *
   * <p>The {@code ORDER BY} replays the comparator verbatim: manufacturer name, ship-type name, the
   * computed insurance tier (LTI &lt; numeric &lt; unset — a bucket, not a column), insurance
   * amount descending within the numeric tier, location name, fitted-first, ship name, and finally
   * the id as a stable tiebreaker so pages never interleave. The amount key casts {@code insurance}
   * to an integer, which is safe because the {@code Ship.insurance} {@code @Pattern} constrains
   * every persisted value to {@code 0}, {@code 1}–{@code 120} or {@code LTI}, and the {@code CASE}
   * guards the cast against the three non-numeric cases ({@code null} / {@code LTI} / {@code 0});
   * the matching branch is never the cast on those rows.
   *
   * <p>{@code search} matches the ship-type name or the manufacturer name (parity with the former
   * client-side filter and the squadron overview); a {@code null}/blank term means "no filter". The
   * {@code cast(:search as string)} null-guard is the proven Postgres-safe idiom (an untyped {@code
   * IS NULL} bind would otherwise resolve to {@code bytea} and break the {@code LIKE}). The value
   * query {@code LEFT JOIN FETCH}es the to-one relations the DTO projection needs (so the page is
   * mapped without N+1) and reuses those fetch-join aliases in the WHERE/ORDER BY; a separate
   * {@code countQuery} is therefore mandatory because a derived count cannot strip a {@code JOIN
   * FETCH}. All fetches are single-valued, so the page limit is applied in SQL (no in-memory
   * pagination).
   *
   * @param ownerId the owning user id; only this user's ships are returned (per-user isolation)
   * @param search optional case-insensitive ship-type/manufacturer name filter; {@code null}/blank
   *     returns every ship the user owns
   * @param pageable page request — pass it <em>unsorted</em> (page+size only); the ordering lives
   *     in the query's {@code ORDER BY}, so any caller-supplied {@code Sort} would only append
   *     noise
   * @return one ordered page of the user's ships
   */
  @Query(
      value =
          """
          SELECT s FROM Ship s
          LEFT JOIN FETCH s.shipType st
          LEFT JOIN FETCH st.manufacturer m
          LEFT JOIN FETCH s.location l
          LEFT JOIN FETCH s.owner
          LEFT JOIN FETCH s.owningOrgUnit
          WHERE s.owner.id = :ownerId
          AND (cast(:search as string) IS NULL
          OR LOWER(st.name) LIKE LOWER(CONCAT('%', cast(:search as string), '%'))
          OR LOWER(m.name) LIKE LOWER(CONCAT('%', cast(:search as string), '%'))
          ) ORDER BY
          LOWER(COALESCE(m.name, '')) ASC,
          LOWER(COALESCE(st.name, '')) ASC,
          CASE WHEN s.insurance = 'LTI' THEN 1
          WHEN s.insurance IS NULL OR s.insurance = '0' THEN 3
          ELSE 2 END ASC,
          CASE WHEN s.insurance IS NULL OR s.insurance = 'LTI' OR s.insurance = '0' THEN 0
          ELSE cast(s.insurance as integer) END DESC,
          LOWER(COALESCE(l.name, '')) ASC,
          CASE WHEN s.fitted = true THEN 0 ELSE 1 END ASC,
          LOWER(COALESCE(s.name, '')) ASC,
          s.id ASC
          """,
      countQuery =
          """
          SELECT COUNT(s) FROM Ship s
          LEFT JOIN s.shipType st
          LEFT JOIN st.manufacturer m
          WHERE s.owner.id = :ownerId
          AND (cast(:search as string) IS NULL
          OR LOWER(st.name) LIKE LOWER(CONCAT('%', cast(:search as string), '%'))
          OR LOWER(m.name) LIKE LOWER(CONCAT('%', cast(:search as string), '%'))
          )
          """)
  Page<Ship> findByOwnerIdFiltered(
      @org.springframework.data.repository.query.Param("ownerId") UUID ownerId,
      @org.springframework.data.repository.query.Param("search") String search,
      Pageable pageable);

  /**
   * Returns every ship owned by any of the given users, eagerly fetching the relations needed for
   * DTO projection. Unlike {@link #findAllScoped}, this is intentionally NOT OrgUnit-scoped: it
   * surfaces the ships of a mission's participants regardless of which OrgUnit each participant
   * belongs to, so a cross-OrgUnit participant's ship can be assigned to a unit.
   *
   * @param ownerIds the owner user ids to match; an empty collection yields an empty list
   * @return ships owned by those users
   */
  @EntityGraph(attributePaths = {"shipType", "location", "owner", "owningOrgUnit"})
  List<Ship> findByOwnerIdIn(java.util.Collection<UUID> ownerIds);

  /**
   * Lists every entity. Overridden here to attach an {@code @EntityGraph}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @Override
  @EntityGraph(attributePaths = {"shipType", "location", "owner", "owningOrgUnit"})
  Page<Ship> findAll(Pageable pageable);

  /**
   * Multi-tenant variant of {@link #findAll(Pageable)}: returns every ship whose owning squadron
   * matches {@code owningSquadronId}, or every ship when {@code owningSquadronId} is {@code null}
   * (admin "all squadrons" mode). Eagerly fetches {@code shipType}, {@code location} and {@code
   * owner} via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"shipType", "location", "owner", "owningOrgUnit"})
  @Query("SELECT s FROM Ship s WHERE " + ScopeSpecifications.SHIP_SCOPE_TRIPLE)
  Page<Ship> findAllScoped(
      @org.springframework.data.repository.query.Param("isAdminAllScope") boolean isAdminAllScope,
      @org.springframework.data.repository.query.Param("activeOrgUnitId") UUID activeOrgUnitId,
      @org.springframework.data.repository.query.Param("memberOrgUnitIds")
          java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Aggregates ships by type for the squadron-overview page: tuple of {@code (shipType, totalCount,
   * fittedCount)} ordered alphabetically by ship-type name. Returns raw {@code Object[]} - the
   * service projects it into the squadron-overview DTO. When {@code isAdminAllScope} is {@code
   * true} the aggregation spans every org unit (admin "all squadrons" mode); otherwise the row set
   * is pre-filtered through the scope-predicate triple. The optional {@code query} narrows the
   * grouped types to those whose ship-type name or manufacturer name contains the term
   * (case-insensitive) — manufacturer is LEFT-joined so types without one still match on their own
   * name. The explicit {@code countQuery} counts distinct ship types: the derived count of a
   * GROUP-BY query would tally ships instead of groups, breaking the page metadata
   * (totalElements/totalPages) this endpoint's pagination UI relies on.
   *
   * @param isAdminAllScope {@code true} iff the caller is an admin without an active selection
   * @param activeOrgUnitId the pinned OrgUnit id, or {@code null} when no pin is active
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin, no-pin path)
   * @param query optional case-insensitive contains-filter on ship-type/manufacturer name; {@code
   *     null} returns every type in scope
   * @param pageable page request (sortable by {@code shipType.name})
   * @return one row per ship type: {@code [ShipType, totalCount, fittedCount]}
   */
  @Query(
      value =
          """
          SELECT s.shipType, COUNT(s), SUM(CASE WHEN s.fitted = true THEN 1 ELSE 0 END)
          FROM Ship s LEFT JOIN s.shipType.manufacturer m
          WHERE
          """
              + ScopeSpecifications.SHIP_SCOPE_TRIPLE
              + " AND (cast(:query as string) IS NULL"
              + "  OR LOWER(s.shipType.name) LIKE LOWER(CONCAT('%', cast(:query as string), '%'))"
              + "  OR LOWER(m.name) LIKE LOWER(CONCAT('%', cast(:query as string), '%'))"
              + " ) GROUP BY s.shipType ORDER BY s.shipType.name ASC",
      countQuery =
          """
          SELECT COUNT(DISTINCT s.shipType)
          FROM Ship s LEFT JOIN s.shipType.manufacturer m
          WHERE
          """
              + ScopeSpecifications.SHIP_SCOPE_TRIPLE
              + " AND (cast(:query as string) IS NULL"
              + "  OR LOWER(s.shipType.name) LIKE LOWER(CONCAT('%', cast(:query as string), '%'))"
              + "  OR LOWER(m.name) LIKE LOWER(CONCAT('%', cast(:query as string), '%'))"
              + " )")
  Page<Object[]> countShipsByType(
      @org.springframework.data.repository.query.Param("isAdminAllScope") boolean isAdminAllScope,
      @org.springframework.data.repository.query.Param("activeOrgUnitId") UUID activeOrgUnitId,
      @org.springframework.data.repository.query.Param("memberOrgUnitIds")
          java.util.Collection<UUID> memberOrgUnitIds,
      @org.springframework.data.repository.query.Param("query") String query,
      Pageable pageable);

  /**
   * OrgUnit-scoped owner-detail lookup for the squadron-overview drill-down: returns the ships of
   * the given types that ALSO fall within the caller's scope, using the same R6.c scope-predicate
   * triple as {@link #countShipsByType} so the per-owner detail rows exactly match the aggregated
   * counts shown next to them. Without the scope clause the owner breakdown leaked rows of ships
   * owned by a foreign OrgUnit that merely shared a ship type with the scoped set — e.g. an admin
   * pinned to a squadron seeing an SK-only member's ship in the overview. The {@code shipType IN}
   * filter narrows the result to the current overview page's types (derived from {@link
   * #countShipsByType}); the scope clause then drops any of those ships that belong to a different
   * OrgUnit. Eagerly fetches {@code owner}, {@code location} and {@code owningOrgUnit} via
   * {@code @EntityGraph}.
   *
   * @param shipTypes the ship types to include (the current overview page's already-scoped types);
   *     an empty collection yields an empty list.
   * @param isAdminAllScope {@code true} iff the caller is an admin without an active selection —
   *     returns every ship of the given types.
   * @param activeOrgUnitId the pinned OrgUnit id, or {@code null} when no pin is active.
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to, consulted only on the
   *     non-admin, no-pin path.
   * @return the scoped ships of those types.
   */
  @EntityGraph(attributePaths = {"owner", "location", "owningOrgUnit"})
  @Query(
      "SELECT s FROM Ship s WHERE s.shipType IN :shipTypes AND "
          + ScopeSpecifications.SHIP_SCOPE_TRIPLE)
  List<Ship> findByShipTypeInScoped(
      @org.springframework.data.repository.query.Param("shipTypes")
          List<de.greluc.krt.profit.basetool.backend.model.ShipType> shipTypes,
      @org.springframework.data.repository.query.Param("isAdminAllScope") boolean isAdminAllScope,
      @org.springframework.data.repository.query.Param("activeOrgUnitId") UUID activeOrgUnitId,
      @org.springframework.data.repository.query.Param("memberOrgUnitIds")
          java.util.Collection<UUID> memberOrgUnitIds);
}
