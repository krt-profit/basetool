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

import de.greluc.krt.profit.basetool.backend.model.PersonalInventoryItem;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link PersonalInventoryItem}. All non-admin lookups MUST use one of
 * the {@code *ByOwnerUserId*} variants in order to enforce the multi-user data isolation rule (see
 * AGENTS.md "MULTI-USER DATA ISOLATION").
 */
@Repository
public interface PersonalInventoryItemRepository
    extends JpaRepository<PersonalInventoryItem, UUID> {

  /** Returns every entity matching the derived {@code findAllByOwnerUserId} criteria. */
  Page<PersonalInventoryItem> findAllByOwnerUserId(UUID ownerUserId, Pageable pageable);

  /**
   * Returns every entity matching the derived {@code
   * findAllByOwnerUserIdAndNameContainingIgnoreCase} criteria.
   */
  Page<PersonalInventoryItem> findAllByOwnerUserIdAndNameContainingIgnoreCase(
      UUID ownerUserId, String nameFragment, Pageable pageable);

  /** Derived Spring-Data query - returns entities matching {@code IdAndOwnerUserId}. */
  Optional<PersonalInventoryItem> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

  /**
   * Deletes every "Mein Inventar" row of the given owner as part of the hard account deletion
   * (REQ-DATA-008). {@code owner_user_id} carries no foreign key to {@code app_user} (V65 declares
   * none at all), so nothing cascades and nothing else in the system would ever remove these rows:
   * before this method existed they survived the account indefinitely, free-text {@code note}
   * included, and were undiscoverable afterwards because every lookup is keyed by the owner sub
   * that no roster can still offer. A returning Keycloak subject would silently re-adopt them.
   *
   * @param ownerUserId the departing owner's {@code app_user.id}; never {@code null}.
   * @return the number of deleted rows, for the audit summary event.
   */
  @Modifying
  @Query("DELETE FROM PersonalInventoryItem p WHERE p.ownerUserId = :ownerUserId")
  int deleteByOwnerUserId(@Param("ownerUserId") UUID ownerUserId);
}
