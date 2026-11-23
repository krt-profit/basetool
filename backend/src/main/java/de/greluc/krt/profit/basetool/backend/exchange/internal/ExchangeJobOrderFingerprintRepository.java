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

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** The stored exchange fingerprints of job orders (REQ-OXCH-003). */
@Repository
public interface ExchangeJobOrderFingerprintRepository
    extends JpaRepository<ExchangeJobOrderFingerprint, UUID> {

  /**
   * Stores an order's changed fingerprint with the instant of the change, inserting the order's
   * first one.
   *
   * @param orderId the order
   * @param fingerprint the new fingerprint
   * @param changedAt when the worker found it changed
   * @param settlementOpen the read's {@code settlementOpen}
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO exchange_job_order_fingerprint
              (job_order_id, fingerprint, changed_at, settlement_open)
          VALUES (:orderId, :fingerprint, :changedAt, :settlementOpen)
          ON CONFLICT (job_order_id) DO UPDATE
             SET fingerprint = EXCLUDED.fingerprint,
                 changed_at = EXCLUDED.changed_at,
                 settlement_open = EXCLUDED.settlement_open
          """,
      nativeQuery = true)
  void write(
      @Param("orderId") UUID orderId,
      @Param("fingerprint") byte[] fingerprint,
      @Param("changedAt") Instant changedAt,
      @Param("settlementOpen") boolean settlementOpen);
}
