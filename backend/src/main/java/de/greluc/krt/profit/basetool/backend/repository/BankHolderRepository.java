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

import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the bank-local holder registry (REQ-BANK-003). Holders are never
 * hard-deleted.
 */
@Repository
public interface BankHolderRepository extends JpaRepository<BankHolder, UUID> {

  /**
   * Looks up the holder row linked to a basetool user — the registration pre-check (one holder per
   * user, V151 unique constraint) and the user-to-holder resolution in the booking flows.
   *
   * @param userId the linked user's id
   * @return the holder row, or empty when the user is not registered as holder
   */
  Optional<BankHolder> findByUserId(UUID userId);

  /**
   * Existence probe for the duplicate-registration pre-check — a clean 409 before the V151 unique
   * constraint would reject the insert.
   *
   * @param userId the linked user's id
   * @return {@code true} when the user already has a holder row
   */
  boolean existsByUserId(UUID userId);

  /**
   * Returns the full holder registry with each linked {@code user} fetch-joined ({@code null} for a
   * deleted user), so the live display name resolves without an N+1 (REQ-BANK-003).
   *
   * @return every holder row with its {@code user} association initialised
   */
  @Query("SELECT h FROM BankHolder h LEFT JOIN FETCH h.user")
  List<BankHolder> findAllWithUser();

  /**
   * Returns the holder rows linked to any of the given users, for the auto-registration reconcile
   * (REQ-BANK-029).
   *
   * @param userIds the linked users' ids
   * @return the matching holder rows (those with a linked user in the set)
   */
  List<BankHolder> findByUserIdIn(Collection<UUID> userIds);

  /**
   * Returns the active, role-managed holders, the candidates the reconcile may deactivate when
   * their user holds no bank role any more (REQ-BANK-029).
   *
   * @return the active holders auto-created from a bank role
   */
  List<BankHolder> findByRoleManagedTrueAndActiveTrue();

  /**
   * Replaces this custodian's handle snapshot with the erasure sentinel for a granted Art. 17
   * request (REQ-SEC-062).
   *
   * <p>Matched by user id, so it must run before the account is deleted.
   *
   * @param userId the member whose custodian registration is anonymised
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      "UPDATE BankHolder h SET h.handle = :sentinel"
          + " WHERE h.user.id = :userId AND h.handle <> :sentinel")
  int anonymiseHandle(@Param("userId") UUID userId, @Param("sentinel") String sentinel);
}
