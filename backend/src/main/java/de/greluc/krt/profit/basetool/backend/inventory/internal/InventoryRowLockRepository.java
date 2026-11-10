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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryScope;
import de.greluc.krt.profit.basetool.backend.kernel.UuidArrayLiteral;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Supplies the row locks a Lager write, a Lager delete and a Materialbörse offer write take, and
 * the delete of exactly the rows a bulk delete locked (ADR-0362, ADR-0363).
 */
@NoRepositoryBean
interface InventoryRowLockRepository extends Repository<InventoryItem, UUID> {

  /**
   * Locks one row with a single locking statement and no join, the one single-row lock of the Lager
   * (ADR-0363); the associations stay lazy. A row another transaction deleted while the lock waited
   * reads as absent. A row the persistence context already holds keeps its state, and a newer
   * committed version of it raises the optimistic-lock conflict.
   *
   * @param id the inventory row id.
   * @return the locked row, or empty when unknown or deleted while the lock waited.
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the persistence
   *     context holds the row at an older version than the one the lock read
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
  Optional<InventoryItem> lockById(@Param("id") UUID id);

  /**
   * Locks the non-personal rows within the given org-unit scope in ascending id order, the first
   * step of the global wipe; personal rows are never touched. Callers must enforce access first.
   *
   * @param isAdminAllScope {@code true} for an admin without an active org unit; locks every
   *     non-personal row
   * @param activeOrgUnitId the single org unit the wipe is scoped to, or {@code null}
   * @param memberOrgUnitIds the caller's org units (non-admin path); empty for admins
   * @return the ids of the locked rows, ascending
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT i.id FROM InventoryItem i WHERE i.personal = false AND "
          + InventoryScope.TRIPLE
          + " ORDER BY i.id")
  List<UUID> lockNonPersonalIdsInScope(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Locks every row of one member, personal and shared, in ascending id order, before the account
   * deletion reads the offers standing on them.
   *
   * @param userId the owner
   * @return the ids of the locked rows, ascending
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT i.id FROM InventoryItem i WHERE i.user.id = :userId ORDER BY i.id")
  List<UUID> lockIdsOfUser(@Param("userId") UUID userId);

  /**
   * Deletes the given rows in one statement; their earmarks and Materialbörse offers cascade.
   *
   * @param ids the rows, a {@code uuid[]} literal ({@link UuidArrayLiteral})
   * @return the number of deleted rows
   */
  @Modifying
  @Query(
      value = "DELETE FROM inventory_item WHERE id = ANY(CAST(:ids AS UUID[]))",
      nativeQuery = true)
  int deleteByIdArray(@Param("ids") String ids);

  /**
   * Deletes the given rows in one statement, binding the ids as one array parameter however many
   * there are; their earmarks and Materialbörse offers cascade.
   *
   * @param ids the rows
   * @return the number of deleted rows
   */
  default int deleteAllByIdIn(@NotNull Collection<UUID> ids) {
    return deleteByIdArray(UuidArrayLiteral.of(ids));
  }
}
