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
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The dirty marks of job orders awaiting a fingerprint recomputation (REQ-OXCH-003). */
@Repository
public interface ExchangeJobOrderMarkRepository extends JpaRepository<ExchangeJobOrderMark, Long> {

  /** The first key of the fingerprint worker's two-key advisory lock, {@code OXFP}; its alone. */
  int WORKER_LOCK_SPACE = 0x4F584650;

  /**
   * Tries to take the fingerprint worker's lock for the rest of the transaction, so one backend
   * instance at a time recomputes.
   *
   * @return whether this transaction holds the lock
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @Query(
      value = "SELECT pg_try_advisory_xact_lock(" + WORKER_LOCK_SPACE + ", 0)",
      nativeQuery = true)
  boolean tryWorkerLock();

  /**
   * Lists every mark of the orders marked longest ago.
   *
   * @param orders how many orders to take
   * @return the marks of those orders that the transaction sees
   */
  @Query(
      value =
          """
          SELECT d.* FROM exchange_job_order_dirty d
          WHERE d.job_order_id IN (
              SELECT m.job_order_id FROM exchange_job_order_dirty m
              GROUP BY m.job_order_id
              ORDER BY min(m.marked_at), m.job_order_id
              LIMIT :orders)
          """,
      nativeQuery = true)
  List<ExchangeJobOrderMark> findMarksOfOldestOrders(@Param("orders") int orders);

  /**
   * Counts the orders that carry at least one mark.
   *
   * @return the number of marked orders
   */
  @Query(
      value = "SELECT count(DISTINCT job_order_id) FROM exchange_job_order_dirty",
      nativeQuery = true)
  long countMarkedOrders();

  /**
   * Finds when the oldest mark was set.
   *
   * @return the oldest mark's instant, or {@code null} when no order is marked
   */
  @Query("SELECT min(m.markedAt) FROM ExchangeJobOrderMark m")
  @Nullable
  Instant findOldestMarkedAt();
}
