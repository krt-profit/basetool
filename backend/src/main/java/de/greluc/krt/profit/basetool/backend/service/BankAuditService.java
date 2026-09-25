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

import de.greluc.krt.profit.basetool.backend.mapper.BankAuditEventMapper;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.ClientAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * Appends rows to the immutable bank audit trail, one per bank mutation, inside the business
 * transaction (REQ-BANK-012); an audit failure rolls the mutation back.
 *
 * <p>Each row snapshots the actor's user id and effective-name handle and records the originating
 * client via {@link ClientAttribution} (REQ-AUDIT-005).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankAuditService {

  private final BankAuditEventRepository auditEventRepository;
  private final AuthHelperService authHelperService;
  private final UserRepository userRepository;
  private final BankAccountRepository accountRepository;
  private final BankAuditEventMapper bankAuditEventMapper;
  private final ClientAttribution clientAttribution;
  private final MeterRegistry meterRegistry;

  /**
   * Appends one audit event for the current caller within the surrounding business transaction.
   *
   * <p>Also increments {@code basetool_bank_audit_events_total{event_type}}, labelled only by the
   * bounded {@link BankAuditEventType} (REQ-OBS-011).
   *
   * @param eventType what happened
   * @param accountId the affected account, or {@code null} for account-less events
   * @param transactionId the created ledger transaction, or {@code null} for non-booking events
   * @param targetUserId the affected user (grantee / holder's linked user), or {@code null}
   * @param details compact {@code key=value} payload, typically an {@link AuditDetails} composer,
   *     or {@code null}
   * @return the persisted audit row
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public BankAuditEvent record(
      @NotNull BankAuditEventType eventType,
      @Nullable UUID accountId,
      @Nullable UUID transactionId,
      @Nullable UUID targetUserId,
      @Nullable CharSequence details) {
    Optional<UUID> actorId = authHelperService.currentUserId();
    String actorHandle =
        actorId.flatMap(userRepository::findById).map(User::getEffectiveName).orElse("system");
    BankAuditEvent event =
        BankAuditEvent.builder()
            .occurredAt(Instant.now())
            .actorUserId(actorId.orElse(null))
            .actorHandle(actorHandle)
            .eventType(eventType)
            .accountId(accountId)
            .transactionId(transactionId)
            .targetUserId(targetUserId)
            .details(details == null ? null : details.toString())
            .clientId(
                clientAttribution.labelOf(authHelperService.currentAuthentication().orElse(null)))
            .build();
    BankAuditEvent saved = auditEventRepository.save(event);
    meterRegistry
        .counter(MetricNames.BANK_AUDIT_EVENTS, MetricNames.TAG_EVENT_TYPE, eventType.name())
        .increment();
    return saved;
  }

  /**
   * Returns one filtered page of the bank audit log for the admin viewer (REQ-BANK-012), with the
   * affected accounts' display numbers resolved in one batched lookup.
   *
   * @param from period start (inclusive), or {@code null}
   * @param to period end (inclusive), or {@code null}
   * @param actorUserId filter on the acting user, or {@code null}
   * @param accountId filter on the affected account, or {@code null}
   * @param eventType filter on the event type, or {@code null}
   * @param clientId filter on the originating client (REQ-AUDIT-005), or {@code null}
   * @param pageable page, size and whitelisted sort
   * @return one page of audit events with resolved account numbers
   */
  @Transactional(readOnly = true)
  public Page<BankAuditEventDto> getEvents(
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable UUID actorUserId,
      @Nullable UUID accountId,
      @Nullable BankAuditEventType eventType,
      @Nullable String clientId,
      @NotNull Pageable pageable) {
    Page<BankAuditEvent> page =
        auditEventRepository.findFiltered(
            from,
            to,
            actorUserId,
            accountId,
            eventType,
            clientAttribution.filterValue(clientId),
            pageable);
    List<UUID> accountIds =
        page.getContent().stream()
            .map(BankAuditEvent::getAccountId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<UUID, String> accountNos =
        accountIds.isEmpty()
            ? Map.of()
            : accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(BankAccount::getId, BankAccount::getAccountNo));
    return page.map(
        event ->
            bankAuditEventMapper.toDto(
                event, event.getAccountId() == null ? null : accountNos.get(event.getAccountId())));
  }

  /**
   * Purges bank audit rows older than a cutoff (REQ-AUDIT-004) and then records an {@code
   * AUDIT_LOG_PURGED} marker carrying the deleted count and cutoff.
   *
   * @param before the exclusive cutoff; rows older than this are removed
   * @return the number of bank audit rows deleted, excluding the purge marker
   */
  @Transactional
  public int purgeBefore(@NotNull Instant before) {
    int deleted = auditEventRepository.deleteByOccurredAtBefore(before);
    record(
        BankAuditEventType.AUDIT_LOG_PURGED,
        null,
        null,
        null,
        AuditDetails.of("deleted", deleted).with("before", before));
    log.info("Purged {} bank audit events older than {}", deleted, before);
    return deleted;
  }
}
