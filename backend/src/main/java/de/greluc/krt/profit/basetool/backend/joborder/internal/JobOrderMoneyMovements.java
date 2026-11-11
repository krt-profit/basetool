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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Books a Bank-free movement once and preserves its durable outcome for recovery. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyMovements {

  private final JobOrderEditor editor;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyHolders holderChoices;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyMovementBasis basis;
  private final JobOrderMoneyIdentityLock identityLock;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderService orders;
  private final JobOrderMoneyEffects effects;

  /**
   * Calculates a movement without creating a holder, booking or audit entry.
   *
   * @param orderId the aUEC order
   * @param dto the observed movement terms
   * @return the effect of these exact terms
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public JobOrderMoneyMovementPreview preview(UUID orderId, MoveJobOrderMoneyDto dto) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    if (queries.responsibilityInvalid(order) || !order.getStatus().isActive()) {
      throw new BusinessConflictException("orders.money.movement.inactive");
    }
    UUID actor = access.requireActiveActor();
    var terms = JobOrderMoneyMovementTerms.from(dto);
    try {
      var resolved = basis.resolve(order, dto, terms, actor, null);
      return resolved.preview(terms, holderChoices.nameOf(resolved.source()));
    } catch (JobOrderMoneyMovementBasis.UnavailableBasis unavailable) {
      throw unavailable.previewProblem();
    } catch (JobOrderMoneyHolders.UnavailableHolderException unavailable) {
      throw unavailable.previewProblem();
    }
  }

  /**
   * Books both sides of a movement or recovers its prior outcome.
   *
   * @param orderId the aUEC order
   * @param dto the operation identity, terms and observed versions
   * @return the accepted result or empty for a durable refusal
   */
  public Optional<JobOrderMoneyOutcome> record(UUID orderId, MoveJobOrderMoneyDto dto) {
    UUID operationId = dto.operationId();
    if (operationId == null) {
      throw new BadRequestException("orders.money.movement.operationRequired");
    }
    var terms = JobOrderMoneyMovementTerms.from(dto);
    RateSnapshot rate = JobOrderMoneyMovementTerms.submittedRate(dto.rate());
    UUID actor = access.actorId();
    Accounts accounts = accounts(orderId, terms);
    identityLock.lockAccounts(actor, accounts.source(), accounts.destination());
    Attempt attempt = new Attempt(operationId, dto, terms, rate, actor, accounts);
    return editor.editMoney(
        orderId,
        order -> mayComplete(order, attempt),
        (order, queueLocked) -> recordLocked(order, attempt, queueLocked));
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> recordLocked(
      JobOrder order, Attempt attempt, boolean queueLocked) {
    access.requireActiveActor();
    UUID id = order.getId();
    var previous = operations.findByOrderIdAndOperationId(id, attempt.id());
    if (previous.isPresent()) {
      return replay(order, attempt, previous.orElseThrow());
    }
    if (operations.isRefused(id, attempt.id())) {
      return refused(order);
    }
    if (!order.getStatus().isActive()) {
      Accounts current = accounts(id, attempt.terms());
      basis.authorize(
          order, attempt.terms(), attempt.actor(), current.source(), current.destination());
      return refuse(order, attempt.id());
    }
    JobOrderMoneyMovementBasis.Resolved resolved;
    try {
      resolved =
          basis.resolve(order, attempt.dto(), attempt.terms(), attempt.actor(), attempt.rate());
    } catch (ObjectOptimisticLockingFailureException
        | JobOrderMoneyHolders.UnavailableHolderException
        | JobOrderMoneyMovementBasis.UnavailableBasis staleBasis) {
      return refuse(order, attempt.id());
    }
    Accounts current =
        new Accounts(resolved.source().getUserId(), resolved.recipient().accountId());
    if (!current.equals(attempt.accounts())
        || resolved.sourceAfter().signum() < 0
        || (resolved.completes() && !queueLocked)) {
      return refuse(order, attempt.id());
    }
    return post(order, attempt, resolved);
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> post(
      JobOrder order, Attempt attempt, JobOrderMoneyMovementBasis.Resolved resolved) {
    JobOrderMoneyMovementTerms terms = attempt.terms();
    var source = resolved.source();
    source.recordChange();
    var destination = resolved.destination();
    UUID destinationId = null;
    boolean newLinkedHolder = false;
    if (destination != null) {
      newLinkedHolder = destination.getId() == null && destination.getUserId() != null;
      destination.recordChange();
      destinationId = Objects.requireNonNull(holders.save(destination).getId());
    }
    var fact =
        operations.saveAndFlush(
            new JobOrderMoneyOperation(
                order.getId(),
                attempt.id(),
                terms.sourceId(),
                attempt.actor(),
                terms.selection(),
                false,
                terms.amount(),
                terms.kind(),
                destinationId,
                terms.recipientId(),
                terms.provision(),
                resolved.rate().rate(),
                resolved.fee()));
    boolean started = order.getStatus() == JobOrderStatus.OPEN;
    if (resolved.completes()) {
      orders.completeJobOrderWithinTransaction(order);
    } else if (started) {
      JobOrderService.changeLifecycle(order, JobOrderStatus.IN_PROGRESS);
    }
    effects.movementRecorded(
        order,
        fact,
        source.getUserId(),
        resolved.recipient().accountId(),
        attempt.actor(),
        started || resolved.completes(),
        newLinkedHolder);
    JobOrderEdit audit =
        JobOrderEdit.audited(
            order,
            AuditEventType.JOB_ORDER_MONEY_MOVED,
            AuditDetails.of("operationId", attempt.id())
                .with("kind", terms.kind())
                .with("amount", terms.amount())
                .with("fee", resolved.fee())
                .with("provision", terms.provision()));
    return new JobOrderEditor.MoneyEdit<>(Optional.of(queries.projectOutcome(fact, source)), audit);
  }

  /**
   * Searches accounts only for a caller who can select another collection holder.
   *
   * @param orderId the aUEC order
   * @param sourceHolderId the holder making the actual outgoing transfer
   * @param query optional name fragment
   * @return eligible receiving accounts
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<JobOrderMoneyHolderCandidate> candidates(
      UUID orderId, UUID sourceHolderId, @Nullable String query) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    UUID actor = access.requireActiveActor();
    JobOrderMoneyHolder source =
        holders
            .findByOrderIdAndId(orderId, sourceHolderId)
            .orElseThrow(() -> new BadRequestException("orders.money.holder.notFound"));
    if (source.getMergedIntoId() != null
        || queries.responsibilityInvalid(order)
        || !order.getStatus().isActive()
        || !(access.responsible(order) || actor.equals(source.getUserId()))) {
      throw new AccessDeniedException("Caller may not choose the receiving holder.");
    }
    return holderChoices.candidates(query);
  }

  private Accounts accounts(UUID orderId, JobOrderMoneyMovementTerms terms) {
    UUID source = holders.accountFor(orderId, terms.sourceId()).orElse(null);
    UUID destination =
        switch (terms.selection().kind()) {
          case LINKED -> terms.selection().reference();
          case EXISTING ->
              terms.recipientId() == null
                  ? holders
                      .accountFor(orderId, Objects.requireNonNull(terms.selection().reference()))
                      .orElse(null)
                  : recipients.accountFor(orderId, terms.recipientId());
          case ACCOUNTLESS -> null;
          case SELF ->
              throw new IllegalStateException("The movement requires an explicit counterparty.");
        };
    return new Accounts(source, destination);
  }

  private boolean mayComplete(JobOrder order, Attempt attempt) {
    JobOrderMoneyMovementTerms terms = attempt.terms();
    UUID orderId = order.getId();
    if (!order.getStatus().isActive()
        || !terms.kind().delivers()
        || operations.existsByOrderIdAndOperationId(orderId, attempt.id())
        || operations.isRefused(orderId, attempt.id())) {
      return false;
    }
    BigDecimal delivered = ledger.delivered(orderId).add(terms.amount());
    if (targets.amountFor(orderId).filter(target -> delivered.compareTo(target) >= 0).isEmpty()) {
      return false;
    }
    access.requireActiveActor();
    basis.authorize(
        order,
        terms,
        attempt.actor(),
        attempt.accounts().source(),
        attempt.accounts().destination());
    return true;
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> replay(
      JobOrder order, Attempt attempt, JobOrderMoneyOperation previous) {
    if (!attempt.actor().equals(previous.getActorId())) {
      throw new AccessDeniedException("Caller may not replay this operation.");
    }
    if (!attempt.terms().matches(previous, attempt.rate())) {
      throw new BusinessConflictException("orders.money.operation.termsMismatch");
    }
    var identities = ledger.read(order.getId());
    var holder = identities.holder(previous.getHolderId());
    UUID destinationId = previous.getDestinationHolderId();
    UUID recipientId = previous.getRecipientId();
    UUID destinationAccount =
        destinationId != null
            ? identities.holder(destinationId).getUserId()
            : recipientId == null ? null : recipients.accountFor(order.getId(), recipientId);
    basis.authorize(
        order, attempt.terms(), attempt.actor(), holder.getUserId(), destinationAccount);
    return new JobOrderEditor.MoneyEdit<>(
        Optional.of(queries.projectOutcome(previous, holder)), JobOrderEdit.unchanged(order));
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> refuse(
      JobOrder order, UUID operationId) {
    operations.reserveRefusal(order.getId(), operationId);
    return refused(order);
  }

  private static JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> refused(JobOrder order) {
    return new JobOrderEditor.MoneyEdit<>(Optional.empty(), JobOrderEdit.unchanged(order));
  }

  private record Accounts(@Nullable UUID source, @Nullable UUID destination) {}

  private record Attempt(
      UUID id,
      MoveJobOrderMoneyDto dto,
      JobOrderMoneyMovementTerms terms,
      RateSnapshot rate,
      UUID actor,
      Accounts accounts) {}
}
