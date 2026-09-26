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
import de.greluc.krt.profit.basetool.backend.support.ClientAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends rows to the immutable activity audit trail (REQ-AUDIT-001, ADR-0037), one per audited
 * mutation, in the same transaction as the business write.
 *
 * <p>Each row snapshots the actor (via {@link AuthHelperService}) so it survives user deletion,
 * derives its {@link AuditDomain} from the event type, and records the originating client through
 * {@link ClientAttribution} (REQ-AUDIT-005).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

  private final AuditEventRepository auditEventRepository;
  private final AuthHelperService authHelperService;
  private final UserRepository userRepository;
  private final AuditEventMapper auditEventMapper;
  private final ClientAttribution clientAttribution;
  private final MeterRegistry meterRegistry;

  /**
   * Appends one audit event for the current caller within the surrounding transaction; the domain
   * comes from {@code eventType.domain()}.
   *
   * @param eventType what happened; its domain pins the row's area
   * @param subjectId the primary affected aggregate's id, or {@code null} for aggregate-less events
   * @param subjectLabel the affected aggregate's label snapshot, or {@code null}
   * @param targetUserId the affected user for user-centric events, or {@code null}
   * @param details compact {@code key=value} payload without user free text, typically an {@link
   *     AuditDetails}; or {@code null}
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
            .actorHandle(truncate(actorHandle))
            .subjectId(subjectId)
            .subjectLabel(truncate(subjectLabel))
            .targetUserId(targetUserId)
            .details(details == null ? null : details.toString())
            .clientId(
                clientAttribution.labelOf(authHelperService.currentAuthentication().orElse(null)))
            .build();
    AuditEvent saved = auditEventRepository.save(event);
    meterRegistry
        .counter(MetricNames.AUDIT_EVENTS, MetricNames.TAG_DOMAIN, eventType.domain().name())
        .increment();
    return saved;
  }

  /**
   * Returns one filtered page of a single area's audit log for the admin viewer (REQ-AUDIT-001).
   *
   * @param domain the area to read
   * @param from period start (inclusive), or {@code null}
   * @param to period end (inclusive), or {@code null}
   * @param actorUserId filter on the acting user, or {@code null}
   * @param eventType filter on the event type, or {@code null}
   * @param clientId filter on the originating client (REQ-AUDIT-005), or {@code null}
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
      @Nullable String clientId,
      @NotNull Pageable pageable) {
    return auditEventRepository
        .findFiltered(
            domain,
            from,
            to,
            actorUserId,
            eventType,
            clientAttribution.filterValue(clientId),
            pageable)
        .map(auditEventMapper::toDto);
  }

  /**
   * Deletes one area's audit rows older than a cutoff (REQ-AUDIT-004) and records the purge as a
   * {@code *_AUDIT_PURGED} event carrying the count and cutoff; a failed marker rolls the delete
   * back.
   *
   * @param domain the area to purge
   * @param before the exclusive cutoff; rows older than this are removed
   * @return the number of audit rows deleted, excluding the purge marker
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
      case HANGAR -> AuditEventType.HANGAR_AUDIT_PURGED;
    };
  }

  /**
   * Clamps a subject label to the {@code subject_label} column width (255), guarding against an
   * over-long composed label (e.g. a very long material + location pair) blowing the insert.
   *
   * @param label the raw label, or {@code null}
   * @return the label clamped to 255 chars, or {@code null}
   */
  @Contract("null -> null")
  private static @Nullable String truncate(@Nullable String label) {
    if (label == null) {
      return null;
    }
    return label.length() <= 255 ? label : label.substring(0, 255);
  }
}
