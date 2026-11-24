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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderScope;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stored exchange fingerprints of job orders and the client set read through them
 * (REQ-OXCH-003).
 */
@Repository
public interface ExchangeJobOrderFingerprintRepository
    extends JpaRepository<ExchangeJobOrderFingerprint, UUID> {

  /** The first key of the window run's two-key advisory lock, {@code OXWN}; its alone. */
  int WINDOW_LOCK_SPACE = 0x4F58574E;

  /**
   * Reads one page of the member's client set: the orders of the read set inside the window that
   * carry a fingerprint, ascending by id after a given one.
   *
   * @param relatingUnitIds the active units the member directly belongs to while holding {@code KRT
   *     Member}
   * @param memberId the member
   * @param afterId the last order delivered, the zero id for the first page
   * @param closedSince the earliest closing instant a terminal order is kept for
   * @param limit the most rows to read
   * @return the orders with the instant of their fingerprint
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.exchange.internal"
          + ".ExchangeJobOrderFingerprintRepository$ClientSetRow(o.id, f.changedAt)"
          + " FROM JobOrder o, ExchangeJobOrderFingerprint f"
          + " WHERE f.jobOrderId = o.id AND o.id > :afterId AND "
          + JobOrderScope.EXCHANGE_READ_SET
          + " AND "
          + JobOrderClientWindow.PREDICATE
          + " ORDER BY o.id")
  List<ClientSetRow> findClientSetAfter(
      @Param("relatingUnitIds") Collection<UUID> relatingUnitIds,
      @Param("memberId") UUID memberId,
      @Param("afterId") UUID afterId,
      @Param("closedSince") Instant closedSince,
      Limit limit);

  /**
   * Selects the given orders that lie in the member's client set: in the read set, inside the
   * window and carrying a fingerprint.
   *
   * @param relatingUnitIds the active units the member directly belongs to while holding {@code KRT
   *     Member}
   * @param memberId the member
   * @param orderIds the orders to decide; never empty
   * @param closedSince the earliest closing instant a terminal order is kept for
   * @return the orders of the set among them, with the instant of their fingerprint
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.exchange.internal"
          + ".ExchangeJobOrderFingerprintRepository$ClientSetRow(o.id, f.changedAt)"
          + " FROM JobOrder o, ExchangeJobOrderFingerprint f"
          + " WHERE f.jobOrderId = o.id AND o.id IN :orderIds AND "
          + JobOrderScope.EXCHANGE_READ_SET
          + " AND "
          + JobOrderClientWindow.PREDICATE)
  List<ClientSetRow> findClientSetAmong(
      @Param("relatingUnitIds") Collection<UUID> relatingUnitIds,
      @Param("memberId") UUID memberId,
      @Param("orderIds") Collection<UUID> orderIds,
      @Param("closedSince") Instant closedSince);

  /**
   * One order of the member's client set.
   *
   * @param orderId the order
   * @param updatedAt the instant of its fingerprint, the read's last change of a carried field
   */
  record ClientSetRow(@NotNull UUID orderId, @NotNull Instant updatedAt) {}

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

  /**
   * Tries to take the window run's lock for the rest of the transaction, so one backend instance at
   * a time ages orders out.
   *
   * @return whether this transaction holds the lock
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @Query(
      value = "SELECT pg_try_advisory_xact_lock(" + WINDOW_LOCK_SPACE + ", 0)",
      nativeQuery = true)
  boolean tryWindowLock();

  /**
   * Records that every fingerprinted order outside the window whose last fingerprint was written
   * inside it left the client set; the fingerprint row's trigger announces each such order where it
   * was last announced (ADR-0406).
   *
   * @param closedSince the earliest closing instant a terminal order is kept for
   * @param now the instant of the run, stored as {@code left_set_at}
   * @return the number of orders aged out
   */
  @Modifying
  @Query(
      "UPDATE ExchangeJobOrderFingerprint f SET f.leftSetAt = :now"
          + " WHERE f.leftSetAt IS NULL AND EXISTS (SELECT o.id FROM JobOrder o"
          + " WHERE o.id = f.jobOrderId AND NOT "
          + JobOrderClientWindow.PREDICATE
          + ")")
  int markLeftSet(@Param("closedSince") Instant closedSince, @Param("now") Instant now);

  /**
   * Lists the fingerprinted orders outside the window that still keep their creating member or
   * their create's key (REQ-OXCH-010).
   *
   * @param closedSince the earliest closing instant a terminal order is kept for
   * @return the orders' ids
   */
  @Query(
      "SELECT o.id FROM JobOrder o, ExchangeJobOrderFingerprint f WHERE f.jobOrderId = o.id"
          + " AND (o.createdByMemberId IS NOT NULL OR o.createIdempotencyKey IS NOT NULL)"
          + " AND NOT "
          + JobOrderClientWindow.PREDICATE)
  List<UUID> findOutsideWithCreator(@Param("closedSince") Instant closedSince);
}
