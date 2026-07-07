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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.mapper.AuditEventMapper;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.model.AuditEvent;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends rows to the immutable activity audit trail shared by the four audited areas
 * (REQ-AUDIT-001, ADR-0037). One row per audited mutation, written in the <em>same transaction</em>
 * as the business write — the {@code MANDATORY} propagation makes calling this outside a
 * transaction a programming error, and an audit insert failure rolls the mutation back (no silent
 * gaps).
 *
 * <p>The actor is resolved from the current security context via {@link AuthHelperService} (never
 * {@code SecurityContextHolder} directly — ArchUnit-enforced) and snapshotted: the row stores both
 * the user id (FK {@code ON DELETE SET NULL}) and the effective-name handle so the trail survives
 * user deletion. The {@link AuditDomain} is derived from the event type itself, so the persisted
 * domain column and the event type can never disagree.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

  private final AuditEventRepository auditEventRepository;
  private final AuthHelperService authHelperService;
  private final UserRepository userRepository;
  private final AuditEventMapper auditEventMapper;
  private final MeterRegistry meterRegistry;

  /**
   * Appends one audit event for the current caller within the surrounding business transaction. The
   * domain is taken from {@code eventType.domain()}.
   *
   * @param eventType what happened (its domain pins the row's area)
   * @param subjectId the primary affected aggregate's id, or {@code null} for aggregate-less events
   * @param subjectLabel the affected aggregate's human-readable label snapshot, or {@code null}
   * @param targetUserId the affected user for user-centric events, or {@code null}
   * @param details compact {@code key=value} details payload (no user free text), or {@code null} —
   *     typically an {@link AuditDetails} composer, stringified via {@link CharSequence#toString()}
   *     before persistence. Taking {@link CharSequence} (not {@code String}) makes the builder the
   *     type-level entry point: a caller hands the composed {@code AuditDetails} directly rather
   *     than hand-concatenating a string.
   * @return the persisted audit row
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AuditEvent record(
      @NotNull AuditEventType eventType,
      @Nullable UUID subjectId,
      @Nullable String subjectLabel,
      @Nullable UUID targetUserId,
      @Nullable CharSequence details) {
    Optional<UUID> actorId = authHelperService.currentUserId();
    String actorHandle =
        actorId.flatMap(userRepository::findById).map(User::getEffectiveName).orElse("system");
    AuditEvent event =
        AuditEvent.builder()
            .occurredAt(Instant.now())
            .domain(eventType.domain())
            .eventType(eventType)
            .actorUserId(actorId.orElse(null))
            // Clamp to the actor_handle column width (255), symmetric with subjectLabel — an
            // over-long effective name must never throw and roll back the business mutation.
            .actorHandle(truncate(actorHandle))
            .subjectId(subjectId)
            .subjectLabel(truncate(subjectLabel))
            .targetUserId(targetUserId)
            // Persist the rendered payload; a null stays null (no details), any other CharSequence
            // (an AuditDetails composer or a raw String) is stringified byte-identically.
            .details(details == null ? null : details.toString())
            .build();
    AuditEvent saved = auditEventRepository.save(event);
    // Per-domain audited-mutation counter (REQ-OBS-011). The domain is the bounded AuditDomain
    // derived from the event type — never subjectLabel/details, which can carry free text/PII.
    // Incremented after a successful save; a later rollback in the same transaction is not undone
    // (standard for a non-transactional counter), an accepted minor drift.
    meterRegistry
        .counter(MetricNames.AUDIT_EVENTS, MetricNames.TAG_DOMAIN, eventType.domain().name())
        .increment();
    return saved;
  }

  /**
   * One filtered page of a single area's audit log for the admin viewer (REQ-AUDIT-001). The domain
   * is the selected tab; the remaining filters are optional.
   *
   * @param domain the area to read
   * @param from period start (inclusive), or {@code null}
   * @param to period end (inclusive), or {@code null}
   * @param actorUserId filter on the acting user, or {@code null}
   * @param eventType filter on the event type, or {@code null}
   * @param pageable page, size and whitelisted sort
   * @return one page of audit events for that area
   */
  @Transactional(readOnly = true)
  public Page<AuditEventDto> getEvents(
      @NotNull AuditDomain domain,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable UUID actorUserId,
      @Nullable AuditEventType eventType,
      @NotNull Pageable pageable) {
    return auditEventRepository
        .findFiltered(domain, from, to, actorUserId, eventType, pageable)
        .map(auditEventMapper::toDto);
  }

  /**
   * Purges one area's audit rows older than a cutoff — the admin retention delete (REQ-AUDIT-004) —
   * and records the purge itself as an audit event so the deletion leaves a trace. The bulk delete
   * runs first; the {@code *_AUDIT_PURGED} marker is written afterwards (its timestamp is newer
   * than the cutoff, so it survives) and carries the deleted count and cutoff in its details. Write
   * transaction on purpose: the marker insert ({@code record}, {@code MANDATORY}) runs inside it,
   * so a failed marker rolls the delete back.
   *
   * @param domain the area to purge (the selected tab)
   * @param before the exclusive cutoff; rows older than this are removed
   * @return the number of audit rows deleted (excludes the purge marker itself)
   */
  @Transactional
  public int purgeBefore(@NotNull AuditDomain domain, @NotNull Instant before) {
    int deleted = auditEventRepository.deleteByDomainAndOccurredAtBefore(domain, before);
    record(
        purgeEventType(domain),
        null,
        null,
        null,
        AuditDetails.of("deleted", deleted).with("before", before));
    log.info("Purged {} audit events for domain {} older than {}", deleted, domain, before);
    return deleted;
  }

  /**
   * The {@code *_AUDIT_PURGED} marker event type for an area's retention purge.
   *
   * @param domain the purged area
   * @return its purge marker event type
   */
  private static @NotNull AuditEventType purgeEventType(@NotNull AuditDomain domain) {
    return switch (domain) {
      case INVENTORY -> AuditEventType.INVENTORY_AUDIT_PURGED;
      case JOB_ORDER -> AuditEventType.JOB_ORDER_AUDIT_PURGED;
      case REFINERY -> AuditEventType.REFINERY_AUDIT_PURGED;
      case PERSONAL_INVENTORY -> AuditEventType.PERSONAL_INVENTORY_AUDIT_PURGED;
      case MISSION -> AuditEventType.MISSION_AUDIT_PURGED;
      case OPERATION -> AuditEventType.OPERATION_AUDIT_PURGED;
      case ROLE -> AuditEventType.ROLE_AUDIT_PURGED;
      case PROMOTION -> AuditEventType.PROMOTION_AUDIT_PURGED;
      case MARKET -> AuditEventType.MARKET_AUDIT_PURGED;
    };
  }

  /**
   * Clamps a subject label to the {@code subject_label} column width (255), guarding against an
   * over-long composed label (e.g. a very long material + location pair) blowing the insert.
   *
   * @param label the raw label, or {@code null}
   * @return the label clamped to 255 chars, or {@code null}
   */
  private static @Nullable String truncate(@Nullable String label) {
    if (label == null) {
      return null;
    }
    return label.length() <= 255 ? label : label.substring(0, 255);
  }
}
