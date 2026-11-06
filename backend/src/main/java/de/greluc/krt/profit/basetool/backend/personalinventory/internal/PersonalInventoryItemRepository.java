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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link PersonalInventoryItem}. All non-admin lookups MUST use one of
 * the {@code *ByOwnerUserId*} variants in order to enforce the multi-user data isolation rule (see
 * AGENTS.md "MULTI-USER DATA ISOLATION").
 *
 * <p>A location is selectable for an owner (REQ-P4K-039) when it is a non-hidden storage place or
 * when one of the owner's items already references it, so every existing item stays editable.
 */
@Repository
public interface PersonalInventoryItemRepository
    extends JpaRepository<PersonalInventoryItem, UUID> {

  /** Returns every entity matching the derived {@code findAllByOwnerUserId} criteria. */
  @EntityGraph(attributePaths = "location")
  Page<PersonalInventoryItem> findAllByOwnerUserId(UUID ownerUserId, Pageable pageable);

  /**
   * Returns every entity matching the derived {@code
   * findAllByOwnerUserIdAndNameContainingIgnoreCase} criteria.
   */
  @EntityGraph(attributePaths = "location")
  Page<PersonalInventoryItem> findAllByOwnerUserIdAndNameContainingIgnoreCase(
      UUID ownerUserId, String nameFragment, Pageable pageable);

  /** Derived Spring-Data query - returns entities matching {@code IdAndOwnerUserId}. */
  Optional<PersonalInventoryItem> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

  /**
   * Finds one location if it is selectable for the owner.
   *
   * @param ownerUserId {@code app_user.id} of the inventory's owner
   * @param locationId the requested {@code location.id}
   * @return the location, or empty when it is unknown or not selectable
   */
  @Query(
      """
      SELECT l FROM Location l WHERE l.id = :locationId
      AND ((l.hidden = false AND l.referenceOnly = false)
      OR EXISTS (SELECT 1 FROM PersonalInventoryItem p
                 WHERE p.ownerUserId = :ownerUserId AND p.location = l))
      """)
  Optional<Location> findSelectableLocation(
      @Param("ownerUserId") UUID ownerUserId, @Param("locationId") UUID locationId);

  /**
   * Lists the locations selectable for the owner whose name contains the LIKE-escaped fragment,
   * case-insensitively, with their city and station.
   *
   * @param ownerUserId {@code app_user.id} of the inventory's owner
   * @param q the LIKE-escaped name fragment, or {@code null} for no filter
   * @param firstPage the first page, whose size caps the result
   * @return the selectable locations, ordered by name
   */
  @Query(
      """
      SELECT l FROM Location l LEFT JOIN FETCH l.city LEFT JOIN FETCH l.spaceStation
      WHERE ((l.hidden = false AND l.referenceOnly = false)
      OR EXISTS (SELECT 1 FROM PersonalInventoryItem p
                 WHERE p.ownerUserId = :ownerUserId AND p.location = l))
      AND (cast(:q as string) IS NULL
      OR LOWER(l.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')) ESCAPE '\\')
      ORDER BY l.name
      """)
  List<Location> findSelectableLocations(
      @Param("ownerUserId") UUID ownerUserId, @Param("q") @Nullable String q, Pageable firstPage);

  /**
   * Deletes every "Mein Inventar" row of the given owner as part of the hard account deletion
   * (REQ-DATA-008), so the purge is counted and audited.
   *
   * @param ownerUserId the departing owner's {@code app_user.id}; never {@code null}
   * @return the number of deleted rows, for the audit summary event
   */
  @Modifying
  @Query("DELETE FROM PersonalInventoryItem p WHERE p.ownerUserId = :ownerUserId")
  int deleteByOwnerUserId(@Param("ownerUserId") UUID ownerUserId);
}
