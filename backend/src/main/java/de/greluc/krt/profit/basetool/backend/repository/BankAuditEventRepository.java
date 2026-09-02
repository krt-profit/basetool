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

import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the append-only bank audit trail (epic #556, REQ-BANK-012). Rows are
 * only ever inserted and read during normal operation; the single exception is the admin-triggered
 * retention purge (REQ-AUDIT-004), a deliberate, itself-audited bulk delete of rows older than a
 * chosen cutoff — there is no automatic retention sweep. Read and purge access are admin-only and
 * enforced at the controller/URL layer, not here.
 */
@Repository
public interface BankAuditEventRepository extends JpaRepository<BankAuditEvent, UUID> {

  /**
   * One filtered page of the audit log for the admin viewer (A2 mockup): every filter is optional,
   * combinable, and applied via the {@code (:param IS NULL OR ...)} pattern established by the
   * inventory queries.
   *
   * @param from period start (inclusive), or {@code null}
   * @param to period end (inclusive), or {@code null}
   * @param actorUserId filter on the acting user, or {@code null}
   * @param accountId filter on the affected account, or {@code null}
   * @param eventType filter on the event type, or {@code null}
   * @param clientId filter on the originating client (REQ-AUDIT-005), or {@code null}
   * @param pageable page, size and whitelisted sort (default {@code occurredAt} descending)
   * @return one page of audit events
   */
  @Query(
      """
      SELECT e FROM BankAuditEvent e WHERE
      (CAST(:from AS timestamp) IS NULL OR e.occurredAt >= :from)
      AND (CAST(:to AS timestamp) IS NULL OR e.occurredAt <= :to)
      AND (CAST(:actorUserId AS uuid) IS NULL OR e.actorUserId = :actorUserId)
      AND (CAST(:accountId AS uuid) IS NULL OR e.accountId = :accountId)
      AND (CAST(:eventType AS string) IS NULL OR e.eventType = :eventType)
      AND (CAST(:clientId AS string) IS NULL OR e.clientId = :clientId)
      """)
  Page<BankAuditEvent> findFiltered(
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("actorUserId") UUID actorUserId,
      @Param("accountId") UUID accountId,
      @Param("eventType") BankAuditEventType eventType,
      @Param("clientId") String clientId,
      Pageable pageable);

  /**
   * Probes whether a transaction has its audit row — a targeted helper for tests and callers. The
   * scheduled integrity sweep uses the set-based {@code
   * BankTransactionRepository#findTransactionsWithoutAuditEvent()} instead (REQ-BANK-012/-020).
   *
   * @param transactionId the transaction id
   * @return {@code true} when an audit event references the transaction
   */
  boolean existsByTransactionId(UUID transactionId);

  /**
   * All bank audit events in a period, oldest first — the chronological feed the period PDF export
   * renders (REQ-AUDIT-001 unified viewer). Unpaged: the export is admin-only and period-bounded.
   *
   * @param from period start (inclusive)
   * @param to period end (inclusive)
   * @return the period's events in ascending time order
   */
  @Query(
      """
      SELECT e FROM BankAuditEvent e WHERE e.occurredAt >= :from AND e.occurredAt <= :to
      ORDER BY e.occurredAt ASC
      """)
  List<BankAuditEvent> findForExport(@Param("from") Instant from, @Param("to") Instant to);

  /**
   * Counts bank audit rows in a period — the export size guard. The export query is unpaged (one
   * document per period), so the report service checks this count first and rejects a period that
   * would still load a pathologically large result set into memory.
   *
   * @param from period start (inclusive)
   * @param to period end (inclusive)
   * @return the number of bank audit events in the period
   */
  @Query(
      "SELECT COUNT(e) FROM BankAuditEvent e WHERE e.occurredAt >= :from AND e.occurredAt <= :to")
  long countForExport(@Param("from") Instant from, @Param("to") Instant to);

  /**
   * Bulk-deletes bank audit rows strictly older than a cutoff — the admin retention purge
   * (REQ-AUDIT-004). The purge is itself audit-logged by the caller <em>after</em> this delete (its
   * row is newer than the cutoff, so it survives).
   *
   * @param before the exclusive cutoff; rows with {@code occurredAt < before} are removed
   * @return the number of rows deleted
   */
  @Modifying
  @Query("DELETE FROM BankAuditEvent e WHERE e.occurredAt < :before")
  int deleteByOccurredAtBefore(@Param("before") Instant before);
}
