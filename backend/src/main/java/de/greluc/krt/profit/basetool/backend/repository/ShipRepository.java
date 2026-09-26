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
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
   * OrgUnit-scoped variant of {@link #resetAllFitted()}: clears the {@code fitted} flag only on
   * ships within the caller's scope triple.
   *
   * @param isAdminAllScope {@code true} iff the caller is admin without an active selection
   * @param activeOrgUnitId pinned OrgUnit id, or {@code null}
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin path)
   * @return how many fitted ships were cleared; ships already unfitted are not counted
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE Ship s SET s.fitted = false WHERE s.fitted = true AND "
          + ScopeSpecifications.SHIP_SCOPE_TRIPLE)
  int resetAllFittedScoped(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Bulk-sets the location of every ship owned by {@code ownerId}, backing the hangar "set home
   * location" action.
   *
   * <p>Does not bump {@code @Version}; clears the persistence context so later reads in the same
   * transaction see the new state.
   *
   * @param ownerId the owning user id whose ships are updated
   * @param location the location to set on every matching ship
   * @return the number of ships updated
   */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Ship s SET s.location = :location WHERE s.owner.id = :ownerId")
  int setLocationForOwner(@Param("ownerId") UUID ownerId, @Param("location") Location location);

  /**
   * Deletes the whole hangar of the given owner as part of the hard account deletion
   * (REQ-DATA-008).
   *
   * <p>Must stay a bulk {@code DELETE}: loading the ships as managed entities before the owner row
   * is removed aborts the flush.
   *
   * @param ownerId the owner whose ships are removed; never {@code null}
   * @return the number of deleted ships, for the audit summary event
   */
  @Modifying
  @Query("DELETE FROM Ship s WHERE s.owner.id = :ownerId")
  int deleteByOwnerId(@Param("ownerId") UUID ownerId);

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
   * Counts one owner's ships per ship type in a single grouped statement, for the hangar import
   * (REQ-DATA-003). Types the owner has no ship of are absent from the result.
   *
   * @param ownerId the hangar owner
   * @return one row per ship type the owner holds, with its ship count
   */
  @Query(
      "SELECT s.shipType.id AS shipTypeId, COUNT(s) AS shipCount FROM Ship s"
          + " WHERE s.owner.id = :ownerId GROUP BY s.shipType.id")
  List<ShipTypeCount> countShipsPerTypeByOwnerId(@Param("ownerId") UUID ownerId);

  /**
   * Row of {@link #countShipsPerTypeByOwnerId(UUID)}: a ship type and how many ships of it one
   * owner holds.
   */
  interface ShipTypeCount {

    /**
     * The ship type the row counts.
     *
     * @return the ship type id
     */
    UUID getShipTypeId();

    /**
     * How many ships of that type the owner holds.
     *
     * @return the count, at least one
     */
    long getShipCount();
  }

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
   * One page of the calling user's own ships, ordered server-side by the hangar's multi-key
   * comparator and optionally filtered by a case-insensitive search term (REQ-HANGAR-002).
   *
   * <p>Order: manufacturer, ship type, insurance tier (LTI, then numeric by amount descending, then
   * unset), location, fitted first, ship name, id.
   *
   * @param ownerId the owning user id; only this user's ships are returned
   * @param search optional case-insensitive ship-type/manufacturer name filter; {@code null}/blank
   *     returns every ship the user owns
   * @param pageable page and size only; the ordering lives in the query, so pass it unsorted
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
      @Param("ownerId") UUID ownerId, @Param("search") String search, Pageable pageable);

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
  List<Ship> findByOwnerIdIn(Collection<UUID> ownerIds);

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
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Aggregates the ships in scope by type for the squadron overview: rows of {@code [ShipType,
   * totalCount, fittedCount]} ordered by ship-type name.
   *
   * <p>The count query counts distinct ship types, so the page metadata reflects groups.
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
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("query") String query,
      Pageable pageable);

  /**
   * Returns the ships of the given types within the caller's scope, for the squadron-overview
   * drill-down; uses the same scope triple as {@link #countShipsByType} so the detail rows match
   * the aggregated counts.
   *
   * @param shipTypes the ship types to include; an empty collection yields an empty list
   * @param isAdminAllScope {@code true} iff the caller is an admin without an active selection
   * @param activeOrgUnitId the pinned OrgUnit id, or {@code null} when no pin is active
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to, consulted only on the
   *     non-admin, no-pin path
   * @return the scoped ships of those types
   */
  @EntityGraph(attributePaths = {"owner", "location", "owningOrgUnit"})
  @Query(
      "SELECT s FROM Ship s WHERE s.shipType IN :shipTypes AND "
          + ScopeSpecifications.SHIP_SCOPE_TRIPLE)
  List<Ship> findByShipTypeInScoped(
      @Param("shipTypes") List<ShipType> shipTypes,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);
}
