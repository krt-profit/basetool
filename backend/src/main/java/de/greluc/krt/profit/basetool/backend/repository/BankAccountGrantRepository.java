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

import de.greluc.krt.profit.basetool.backend.model.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrantId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link BankAccountGrant} rows (REQ-BANK-009). {@code existsById(new
 * BankAccountGrantId(userId, accountId))} is the "may this user see this account" probe.
 */
@Repository
public interface BankAccountGrantRepository
    extends JpaRepository<BankAccountGrant, BankAccountGrantId> {

  /**
   * All grants on one account with grantee and account pre-fetched, for the per-account grants
   * matrix (G1 mockup), ordered by grantee handle.
   *
   * @param accountId the account whose grants to list
   * @return the account's grant rows
   */
  @EntityGraph(attributePaths = {"user", "account"})
  @Query(
      """
      SELECT g FROM BankAccountGrant g WHERE g.id.accountId = :accountId
      ORDER BY g.user.username
      """)
  List<BankAccountGrant> findByAccountId(@Param("accountId") UUID accountId);

  /**
   * All grants of one employee with grantee and account pre-fetched, for the per-employee matrix
   * view (G2 toggle), ordered by account number.
   *
   * @param userId the grantee whose grants to list
   * @return the user's grant rows
   */
  @EntityGraph(attributePaths = {"user", "account"})
  @Query(
      """
      SELECT g FROM BankAccountGrant g WHERE g.id.userId = :userId
      ORDER BY g.account.accountNo
      """)
  List<BankAccountGrant> findByUserId(@Param("userId") UUID userId);

  /**
   * Every grant row with references pre-fetched, for the "Alle Konten" matrix view, ordered by
   * account number then grantee handle.
   *
   * @return all grant rows
   */
  @EntityGraph(attributePaths = {"user", "account"})
  @Query("SELECT g FROM BankAccountGrant g ORDER BY g.account.accountNo, g.user.username")
  List<BankAccountGrant> findAllWithReferences();
}
