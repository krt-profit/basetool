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
 * Spring Data repository for the bank-local holder registry (epic #556, REQ-BANK-003). Holders are
 * never hard-deleted (the ledger references them with {@code ON DELETE RESTRICT}); the registry
 * list is unbounded by design — it holds one row per custodian player, a few dozen at org scale.
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
   * The full registry with each holder's linked {@code user} fetch-joined in one statement — the
   * management "Halter" tab (W1 mockup) and every holder-select dropdown. The user is eager-loaded
   * (LEFT JOIN, {@code null} for a deleted user) so the mapper can resolve the holder's live
   * display name ({@link BankHolder#getDisplayName()}, REQ-BANK-003) without an N+1; the service
   * sorts the mapped rows by that live name (the stored {@code handle} order would no longer match
   * what is shown once a user renamed themselves).
   *
   * @return every holder row with its {@code user} association initialised
   */
  @Query("SELECT h FROM BankHolder h LEFT JOIN FETCH h.user")
  List<BankHolder> findAllWithUser();

  /**
   * The holder rows linked to any of the given users — the batch lookup of the auto-registration
   * reconcile (REQ-BANK-029) over the current bank-role roster, avoiding a per-user {@code
   * findById}.
   *
   * @param userIds the linked users' ids
   * @return the matching holder rows (those with a linked user in the set)
   */
  List<BankHolder> findByUserIdIn(Collection<UUID> userIds);

  /**
   * The active, role-managed holders — the candidates the reconcile may auto-deactivate when their
   * user no longer holds any bank role (REQ-BANK-029). Manually registered holders ({@code
   * role_managed = false}) are excluded by construction.
   *
   * @return the active holders auto-created from a bank role
   */
  List<BankHolder> findByRoleManagedTrueAndActiveTrue();

  /**
   * Replaces this custodian's handle snapshot with the erasure sentinel, for a granted Art. 17
   * request (REQ-SEC-062).
   *
   * <p>The column exists precisely so the registry survives the account: {@code user_id} is {@code
   * ON DELETE SET NULL} and {@code handle} is {@code NOT NULL}, and {@code
   * BankHolder#getDisplayName()} falls back to it the moment the foreign key nulls out. So it is
   * the one snapshot that becomes <em>more</em> visible after a deletion, and it was the most
   * conspicuous omission from the first version of this erasure.
   *
   * <p>Matched by the user id, so it reaches the row while the account still exists — which is
   * ordered, {@code HandleAnonymisationService} runs before the deletion.
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
