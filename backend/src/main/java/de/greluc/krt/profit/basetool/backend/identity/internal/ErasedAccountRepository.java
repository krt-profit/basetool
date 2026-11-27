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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Read/write access to the erasure register (REQ-DATA-048). */
@Repository
public interface ErasedAccountRepository extends JpaRepository<ErasedAccount, UUID> {

  /**
   * Registers a deleted account; a second registration of the same id keeps the first time.
   *
   * @param id the deleted account's former {@code app_user.id}
   * @param erasedAt when the account was deleted
   * @return {@code 1} when the entry was added, {@code 0} when it existed
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO erased_account (id, erased_at) VALUES (:id, :erasedAt)
          ON CONFLICT (id) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(@Param("id") UUID id, @Param("erasedAt") Instant erasedAt);

  /**
   * Returns every registered id, oldest deletion first.
   *
   * @return the registered ids
   */
  @Query("SELECT e.id FROM ErasedAccount e ORDER BY e.erasedAt, e.id")
  List<UUID> findAllIds();

  /**
   * Counts the registered ids that still have an account row, which a restore or a re-created
   * account leaves behind until the erasure job deletes them again.
   *
   * @return the number of registered ids with an {@code app_user} row
   */
  @Query(
      """
      SELECT COUNT(e) FROM ErasedAccount e
      WHERE EXISTS (SELECT 1 FROM User u WHERE u.id = e.id)
      """)
  long countWithAccountRow();

  /**
   * Removes the entries of accounts deleted before {@code cutoff}.
   *
   * @param cutoff the oldest deletion time that stays registered
   * @return the number of entries removed
   */
  @Modifying
  @Query("DELETE FROM ErasedAccount e WHERE e.erasedAt < :cutoff")
  int deleteErasedBefore(@Param("cutoff") Instant cutoff);
}
