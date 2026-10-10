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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRevocationRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to the per-member client revocations (REQ-XCH-008). */
public interface ExchangeClientRevocationRepository
    extends JpaRepository<ExchangeClientRevocation, ExchangeClientRevocation.Key> {

  /**
   * Records that a member disconnected a client, replacing an earlier time.
   *
   * @param exchangeClientId the registry client
   * @param userId the member
   * @param revokedAt the revocation time
   * @return the number of rows written
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO exchange_client_revocation (exchange_client_id, user_id, revoked_at)
          VALUES (:exchangeClientId, :userId, :revokedAt)
          ON CONFLICT (exchange_client_id, user_id) DO UPDATE SET revoked_at = EXCLUDED.revoked_at
          """,
      nativeQuery = true)
  int upsert(
      @Param("exchangeClientId") UUID exchangeClientId,
      @Param("userId") UUID userId,
      @Param("revokedAt") Instant revokedAt);

  /**
   * Deletes every client revocation made before the cutoff (REQ-XCH-035).
   *
   * @param cutoff the oldest revocation still kept
   * @return the number of revocations deleted
   */
  @Modifying
  @Query(
      value = "DELETE FROM exchange_client_revocation WHERE revoked_at < :cutoff",
      nativeQuery = true)
  int deleteRevokedBefore(@Param("cutoff") Instant cutoff);

  /**
   * Lists the revocations after a point in time with their client ids.
   *
   * @param since the oldest revocation still enforced
   * @return the rows
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRevocationRow(
          c.clientId, r.key.userId, r.revokedAt)
      FROM ExchangeClientRevocation r, ExchangeClient c
      WHERE c.id = r.key.exchangeClientId AND r.revokedAt > :since
      """)
  List<ExchangeRevocationRow> findRevokedSince(@Param("since") Instant since);

  /**
   * Lists one member's revocations with their client ids.
   *
   * @param userId the member
   * @return the rows
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeRevocationRow(
          c.clientId, r.key.userId, r.revokedAt)
      FROM ExchangeClientRevocation r, ExchangeClient c
      WHERE c.id = r.key.exchangeClientId AND r.key.userId = :userId
      """)
  List<ExchangeRevocationRow> findAllByUserId(@Param("userId") UUID userId);
}
