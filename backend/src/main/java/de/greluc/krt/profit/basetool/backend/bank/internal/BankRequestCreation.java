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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static de.greluc.krt.profit.basetool.backend.bank.api.BankAmounts.plain;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCreatedEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates and announces ordinary and linked Bank booking requests. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class BankRequestCreation {
  private final BankBookingRequestRepository requestRepository;
  private final BankAuditService bankAuditService;
  private final UserRepository userRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final BankLinkedRequestLiveSync linkedLiveSync;

  /**
   * Persists fixed linked terms in the existing Bank request lifecycle.
   *
   * @param attempt the submitted Bank attempt
   * @param terms the fixed Bank terms
   * @param actor the authenticated applicant
   * @param routing the existing Bank approval decision
   * @return the pending request's id
   */
  @NotNull
  UUID createLinked(
      @NotNull BankLinkedAttemptRecord attempt,
      @NotNull BankLinkedBooking terms,
      @NotNull BankActor actor,
      @NotNull BankAdmission.Admitted routing) {
    BankBookingRequest request = new BankBookingRequest();
    request.setLinkedAttempt(attempt);
    request.setPurposeKind(attempt.getPurposeKind());
    request.setPurposeRef(attempt.getPurposeRef());
    request.setType(terms.type());
    request.setAmount(terms.amount());
    request.setNote(terms.note());
    request.setJustification(terms.justification());
    request.setAccount(terms.account());
    request.setTargetAccount(terms.target());
    request.setRequestedHolder(terms.holder());
    request.setRequestedTargetHolder(terms.targetHolder());
    request.applyCounterparty(terms.counterparty());
    request.setRequestedBy(actor.userId());
    request.setRequesterHandle(resolveHandle(actor.userId()));
    request.setRequiresOwnerApproval(routing.requiresApproval());
    request.setApplicableLimit(routing.applicableLimit());
    request.setRequiredApprover(routing.requiredApprover());
    BankBookingRequest saved = saveAndAnnounce(request);
    linkedLiveSync.changed();
    return saved.getId();
  }

  /**
   * Stores and announces a newly raised request.
   *
   * @param request the pending request
   * @return the stored request
   */
  @NotNull
  BankBookingRequest saveAndAnnounce(@NotNull BankBookingRequest request) {
    BankBookingRequest saved = requestRepository.save(request);
    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_CREATED,
        saved.accountId(),
        null,
        saved.getRequestedBy(),
        saved.getType()
            + " request "
            + plain(saved.getAmount())
            + " aUEC "
            + saved.shortId()
            + (saved.isRequiresOwnerApproval() ? " (needs approval)" : ""));
    BankAccount account = saved.getAccount();
    OrgUnit orgUnit = account == null ? null : account.getOrgUnit();
    eventPublisher.publishEvent(
        new BankBookingRequestCreatedEvent(
            saved.getId(),
            saved.accountId(),
            saved.getType(),
            saved.getAmount(),
            saved.accountNo(),
            saved.getRequesterHandle(),
            orgUnit == null ? null : orgUnit.getShorthand(),
            saved.getRequestedBy()));
    return saved;
  }

  /**
   * Snapshots a user's effective name for the requester/decider handle column, falling back to a
   * neutral marker when the user cannot be resolved (never a PII leak).
   *
   * @param userId the user id, possibly {@code null}
   * @return the effective name, or {@code "unknown"}
   */
  String resolveHandle(@Nullable UUID userId) {
    return Optional.ofNullable(userId)
        .flatMap(userRepository::findById)
        .map(User::getEffectiveName)
        .orElse("unknown");
  }
}
