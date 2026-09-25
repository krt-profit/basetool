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

import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.model.AuditEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
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
 * Spring Data repository for the append-only activity audit trail (REQ-AUDIT-001).
 *
 * <p>Rows are only inserted and read, except for the retention purge (REQ-AUDIT-004) and the Art.
 * 17 handle anonymisation. Access control lives in the controller/URL layer.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  /**
   * Returns one filtered page of a single domain's audit log for the admin viewer; every filter
   * except the domain is optional.
   *
   * @param domain the area to read (the selected tab)
   * @param from period start (inclusive), or {@code null}
   * @param to period end (inclusive), or {@code null}
   * @param actorUserId filter on the acting user, or {@code null}
   * @param eventType filter on the event type, or {@code null}
   * @param clientId filter on the originating client (REQ-AUDIT-005), or {@code null}
   * @param pageable page, size and whitelisted sort (default {@code occurredAt} descending)
   * @return one page of audit events
   */
  @Query(
      """
      SELECT e FROM AuditEvent e WHERE e.domain = :domain
      AND (CAST(:from AS timestamp) IS NULL OR e.occurredAt >= :from)
      AND (CAST(:to AS timestamp) IS NULL OR e.occurredAt <= :to)
      AND (CAST(:actorUserId AS uuid) IS NULL OR e.actorUserId = :actorUserId)
      AND (CAST(:eventType AS string) IS NULL OR e.eventType = :eventType)
      AND (CAST(:clientId AS string) IS NULL OR e.clientId = :clientId)
      """)
  Page<AuditEvent> findFiltered(
      @Param("domain") AuditDomain domain,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("actorUserId") UUID actorUserId,
      @Param("eventType") AuditEventType eventType,
      @Param("clientId") String clientId,
      Pageable pageable);

  /**
   * Returns all events of one domain in a period, oldest first, for the period PDF export. Unpaged.
   *
   * @param domain the area to export
   * @param from period start (inclusive)
   * @param to period end (inclusive)
   * @return the period's events in ascending time order
   */
  @Query(
      """
      SELECT e FROM AuditEvent e WHERE e.domain = :domain
      AND e.occurredAt >= :from AND e.occurredAt <= :to
      ORDER BY e.occurredAt ASC
      """)
  List<AuditEvent> findForExport(
      @Param("domain") AuditDomain domain, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Counts one domain's audit rows in a period, used as the size guard before the unpaged export.
   *
   * @param domain the area to count
   * @param from period start (inclusive)
   * @param to period end (inclusive)
   * @return the number of events in the period
   */
  @Query(
      """
      SELECT COUNT(e) FROM AuditEvent e WHERE e.domain = :domain
      AND e.occurredAt >= :from AND e.occurredAt <= :to
      """)
  long countForExport(
      @Param("domain") AuditDomain domain, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Bulk-deletes one domain's audit rows strictly older than a cutoff, for the retention purge
   * (REQ-AUDIT-004). Never touches another domain.
   *
   * @param domain the area to purge (the selected tab)
   * @param before the exclusive cutoff; rows with {@code occurredAt < before} are removed
   * @return the number of rows deleted
   */
  @Modifying
  @Query("DELETE FROM AuditEvent e WHERE e.domain = :domain AND e.occurredAt < :before")
  int deleteByDomainAndOccurredAtBefore(
      @Param("domain") AuditDomain domain, @Param("before") Instant before);

  /**
   * Whether one domain holds any audit row older than a cutoff; checked by the scheduled retention
   * sweep (REQ-AUDIT-006) so it only purges, and writes its purge marker, when something matches.
   *
   * @param domain the area to check
   * @param before the exclusive cutoff
   * @return {@code true} when at least one row of that domain is older than the cutoff
   */
  boolean existsByDomainAndOccurredAtBefore(AuditDomain domain, Instant before);

  /**
   * Replaces this member's actor handle snapshot with the erasure sentinel for a granted Art. 17
   * request (REQ-SEC-062).
   *
   * <p>The only mutation of this otherwise append-only table besides the purge; it changes who a
   * row names, nothing else. Matched by {@code actorUserId}, so it reaches rows only while the
   * account still exists.
   *
   * @param userId the member whose handle snapshots are erased
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      "UPDATE AuditEvent e SET e.actorHandle = :sentinel"
          + " WHERE e.actorUserId = :userId AND e.actorHandle <> :sentinel")
  int anonymiseActorHandle(@Param("userId") UUID userId, @Param("sentinel") String sentinel);

  /**
   * Replaces every subject label that equals this member's handle with the erasure sentinel
   * (REQ-SEC-062).
   *
   * <p>Matches the whole label case-insensitively, never a substring, so a label that merely
   * contains the handle stays untouched.
   *
   * @param handle the spelling to erase; compared case-insensitively against the whole label
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      "UPDATE AuditEvent e SET e.subjectLabel = :sentinel"
          + " WHERE lower(e.subjectLabel) = lower(:handle) AND e.subjectLabel <> :sentinel")
  int anonymiseSubjectLabel(@Param("handle") String handle, @Param("sentinel") String sentinel);
}
