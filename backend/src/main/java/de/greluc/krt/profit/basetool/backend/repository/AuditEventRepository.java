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
 * Spring Data repository for the append-only activity audit trail (REQ-AUDIT-001). Rows are only
 * ever inserted and read during normal operation; the single exception is the admin-triggered
 * retention purge (REQ-AUDIT-004), a deliberate, itself-audited bulk delete of rows older than a
 * chosen cutoff — there is no automatic retention sweep. Read and purge access are admin-only and
 * enforced at the controller/URL layer, not here.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  /**
   * One filtered page of a single domain's audit log for the admin viewer. The domain is always
   * pinned (the active tab); every other filter is optional, combinable, and applied via the {@code
   * (:param IS NULL OR ...)} pattern established by the bank/inventory queries.
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
   * All events of one domain in a period, oldest first — the chronological feed the period PDF
   * export renders. Unpaged on purpose: the export is admin-only, period-bounded and rendered in
   * one document.
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
   * Counts one domain's audit rows in a period — the export size guard. The export queries are
   * unpaged (one document per period), so the report service checks this count first and rejects a
   * period that would still load a pathologically large result set into memory.
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
   * Bulk-deletes one domain's audit rows strictly older than a cutoff — the admin retention purge
   * (REQ-AUDIT-004). Scoped to the selected tab's domain so a purge of one area never touches
   * another. The purge is itself audit-logged by the caller <em>after</em> this delete (its row is
   * newer than the cutoff, so it survives).
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
   * Whether one domain holds any audit row older than a cutoff. Asked by the scheduled retention
   * sweep (REQ-AUDIT-006) before it purges that domain.
   *
   * <p>The sweep needs this because {@code purgeBefore} writes its {@code *_AUDIT_PURGED} marker
   * unconditionally, which is right for an admin's deliberate act — "I purged, and nothing matched"
   * is a fact worth recording — and wrong for a daily job, which would otherwise mint ten markers a
   * day forever and turn the retention mechanism into its own retention problem. Asking first keeps
   * the manual purge's semantics untouched.
   *
   * @param domain the area to check
   * @param before the exclusive cutoff
   * @return {@code true} when at least one row of that domain is older than the cutoff
   */
  boolean existsByDomainAndOccurredAtBefore(AuditDomain domain, Instant before);

  /**
   * Replaces this member's handle snapshot with the erasure sentinel, for a granted Art. 17 request
   * (REQ-SEC-062).
   *
   * <p><b>This is the only mutation of an otherwise append-only table, and it is deliberate.</b>
   * The trail's worth rests on rows never being rewritten, so the operation is admin-gated, is
   * itself audit-logged (a {@code HANDLE_SNAPSHOTS_ANONYMISED} marker written afterwards, which the
   * update therefore does not touch), and changes nothing about <em>what happened</em> — only who
   * it names. Row counts, timestamps, event types and subjects are untouched.
   *
   * <p>Matched by {@code actorUserId}, so it only reaches rows while the account still exists. Once
   * the FK has nulled out, the handle is the only remaining link and the admin Personensuche
   * (REQ-SEC-060) is the way to find those rows.
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
   * Replaces a subject label that <em>is</em> this member's name with the erasure sentinel
   * (REQ-SEC-062).
   *
   * <p>A second column on the same rows, and it was missed the first time: {@code
   * anonymiseActorHandle} scrubs {@code actor_handle} and never touches {@code subject_label}, so a
   * granted erasure left rows literally half-anonymised — scrubbed actor, intact name, same row —
   * and the deletion-request events were themselves labelled with the requester's name. Those call
   * sites now pass {@code null}, per the REQ-AUDIT-001 convention that a subject label is a
   * non-personal display label; this query is what reaches the rows already written.
   *
   * <p><b>Exact match, not a substring replace.</b> A label that merely <em>contains</em> the
   * handle is a job-order title naming that order's contact person, which is a different person's
   * name on a row about a different act; rewriting it would erase somebody who did not ask.
   * Case-insensitive because a label can be assembled from text a human typed.
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

  /**
   * Replaces this member's name where it occurs inside a details payload (REQ-SEC-062).
   *
   * <p>{@code details} is a bare {@code CharSequence} on {@code AuditService#record}, so nothing
   * forces a caller through the {@code AuditDetails} builder and nothing stops a handle being
   * concatenated into it. REQ-AUDIT-001 forbids it, {@code PersonSearchTargets} exempted the column
   * on the strength of that rule, and the bank services falsified it — which is why the search now
   * covers the column and the erasure now reaches it.
   *
   * <p><b>Substring replace, and case-sensitive.</b> The payload is machine-written, so the handle
   * appears in it exactly as the application spelled it; a case-insensitive replace would need
   * {@code regexp_replace} and a native query, for a case that cannot arise. The surrounding
   * key/value text is preserved, so the row still says what happened.
   *
   * @param handle the spelling to erase
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      "UPDATE AuditEvent e SET e.details = REPLACE(e.details, :handle, :sentinel)"
          + " WHERE e.details LIKE CONCAT('%', :handle, '%')")
  int anonymiseDetails(@Param("handle") String handle, @Param("sentinel") String sentinel);
}
