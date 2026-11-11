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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Books actual losses, releases, beneficiary payments and requester returns. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneySettlements {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyIdentityLock identities;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyHolders holderChoices;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneySettlementBasis basis;
  private final JobOrderMoneySettlementWriter writer;
  private final JobOrderLinkedMoneySettlements linked;
  private final JobOrderMoneyAccountAdjustments accountAdjustments;
  private final JobOrderMoneyAccountReturns accountReturns;

  /**
   * Calculates a settlement at its observed basis without creating a fact.
   *
   * @param orderId the readable order
   * @param dto the actual settlement terms and observed revisions
   * @return exact net, fee, funding and balance effects
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public JobOrderMoneyMovementPreview preview(UUID orderId, SettleJobOrderMoneyDto dto) {
    JobOrder order = readableSettlement(orderId);
    if (hasAccountReturn(dto)) {
      return accountReturns.preview(orderId, dto);
    }
    var terms = JobOrderMoneySettlementTerms.from(dto);
    if (dto.sourceAccountHoldingId() != null) {
      terms.submittedRate(dto.rate());
      return accountAdjustments.preview(order, terms, dto.sourceVersion());
    }
    try {
      var resolved = basis.resolve(order, dto, terms, access.requireActiveActor(), null);
      if (dto.bank() != null) {
        linked.validateParties(resolved, dto.bank());
      }
      return resolved.preview(terms);
    } catch (JobOrderMoneySettlementBasis.UnavailableSettlement unavailable) {
      throw unavailable.previewProblem();
    } catch (JobOrderMoneyHolders.UnavailableHolderException unavailable) {
      throw unavailable.previewProblem();
    }
  }

  /**
   * Finds a receiving account for an authorized actual return or beneficiary payment.
   *
   * @param orderId the readable order
   * @param kind the settlement purpose
   * @param holderId the holder funding a beneficiary payment
   * @param recipientId the requester designation making a return
   * @param query the optional search text
   * @return bounded active-account selections
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<JobOrderMoneyHolderCandidate> candidates(
      UUID orderId,
      JobOrderMoneyKind kind,
      @Nullable UUID holderId,
      @Nullable UUID recipientId,
      @Nullable String query) {
    JobOrder order = readableSettlement(orderId);
    UUID actor = access.requireActiveActor();
    UUID payer =
        switch (kind) {
          case SETTLEMENT -> {
            if (holderId == null || recipientId != null) {
              throw JobOrderMoneySettlementTerms.invalidSelection();
            }
            var source =
                holders
                    .findByOrderIdAndId(orderId, holderId)
                    .orElseThrow(JobOrderMoneySettlementTerms::invalidSelection);
            if (source.getMergedIntoId() != null) {
              throw new BusinessConflictException("orders.money.holder.linkChanged");
            }
            yield source.getUserId();
          }
          case REQUESTER_RETURN -> {
            if (recipientId == null) {
              throw JobOrderMoneySettlementTerms.invalidSelection();
            }
            var designation = recipients.require(orderId, recipientId);
            if (designation.isSuperseded()) {
              throw new BusinessConflictException("orders.money.recipient.identity.changed");
            }
            yield recipients.party(designation).accountId();
          }
          case PROVISION,
              COLLECTION,
              HANDOVER,
              PURPOSE_HANDOVER,
              RELEASE,
              SHORTAGE,
              PURPOSE_INFLOW,
              PURPOSE_OUTFLOW,
              CORRECTION ->
              throw JobOrderMoneySettlementTerms.invalidSelection();
        };
    basis.authorize(order, kind, actor, payer);
    try {
      JobOrderMoneySettlementBasis.assertSettlementStatus(order);
    } catch (JobOrderMoneySettlementBasis.UnavailableSettlement unavailable) {
      throw unavailable.previewProblem();
    }
    return holderChoices.candidates(query);
  }

  /**
   * Books one actual settlement or returns the original accepted outcome.
   *
   * @param orderId the order owning the settlement
   * @param dto the operation identity, exact terms and observed revisions
   * @return the accepted result or empty for a durable refusal
   */
  public Optional<JobOrderMoneyOutcome> settle(UUID orderId, SettleJobOrderMoneyDto dto) {
    UUID id = dto.operationId();
    if (id == null) {
      throw new BadRequestException("orders.money.movement.operationRequired");
    }
    if (hasAccountReturn(dto)) {
      return accountReturns.record(orderId, dto);
    }
    var terms = JobOrderMoneySettlementTerms.from(dto);
    RateSnapshot rate = terms.submittedRate(dto.rate());
    if (dto.sourceAccountHoldingId() != null) {
      return accountAdjustments.record(orderId, id, terms, dto.sourceVersion());
    }
    UUID actor = access.actorId();
    Accounts accounts = accounts(orderId, terms);
    identities.lockAffectedAccounts(actor, accounts.linked());
    var attempt = new Attempt(id, actor, dto, terms, rate, accounts);
    return editor.editMoney(
        orderId,
        order -> mayReopen(order, attempt),
        (order, queueLocked) -> recordLocked(order, attempt, queueLocked));
  }

  private static boolean hasAccountReturn(SettleJobOrderMoneyDto dto) {
    if (dto.destinationAccountHoldingId() != null && dto.sourceAccountHoldingId() != null) {
      throw JobOrderMoneySettlementTerms.invalidSelection();
    }
    return dto.destinationAccountHoldingId() != null;
  }

  private JobOrder readableSettlement(UUID orderId) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    if (queries.responsibilityInvalid(order)) {
      throw new AccessDeniedException("The order's responsibility is ineligible.");
    }
    return order;
  }

  private boolean mayReopen(JobOrder order, Attempt attempt) {
    UUID orderId = order.getId();
    if (!attempt.terms().requesterReturn()
        || order.getStatus() != JobOrderStatus.COMPLETED
        || operations.existsByOrderIdAndOperationId(orderId, attempt.id())
        || linked.hasAttempt(orderId, attempt.id())
        || operations.isRefused(orderId, attempt.id())) {
      return false;
    }
    basis.authorize(
        order, attempt.terms().kind(), access.requireActiveActor(), attempt.accounts().requester());
    BigDecimal delivered = ledger.delivered(orderId).subtract(attempt.terms().amount());
    return targets.amountFor(orderId).filter(target -> delivered.compareTo(target) < 0).isPresent();
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> recordLocked(
      JobOrder order, Attempt attempt, boolean queueLocked) {
    access.requireActiveActor();
    UUID orderId = order.getId();
    var bank = attempt.dto().bank();
    if (bank != null) {
      var previous =
          linked.replay(
              order, attempt.id(), attempt.actor(), attempt.terms(), attempt.rate(), bank);
      if (previous.isPresent()) {
        return unchanged(order, previous);
      }
    }
    var accepted = operations.findByOrderIdAndOperationId(orderId, attempt.id());
    if (accepted.isPresent()) {
      return replay(order, attempt, accepted.orElseThrow());
    }
    if (operations.isRefused(orderId, attempt.id())) {
      return unchanged(order, Optional.empty());
    }
    var current = accounts(orderId, attempt.terms());
    basis.authorize(
        order,
        attempt.terms().kind(),
        attempt.actor(),
        attempt.terms().requesterReturn() ? current.requester() : current.source());
    ledger.requireIndependentOperation(orderId, attempt.id());
    if (!current.equals(attempt.accounts())) {
      return refuse(order, attempt.id());
    }
    JobOrderMoneySettlementBasis.Resolved settled;
    try {
      settled =
          basis.resolve(order, attempt.dto(), attempt.terms(), attempt.actor(), attempt.rate());
    } catch (JobOrderMoneySettlementBasis.UnavailableSettlement
        | JobOrderMoneyHolders.UnavailableHolderException
        | ObjectOptimisticLockingFailureException unavailable) {
      return refuse(order, attempt.id());
    }
    if (settled.sourceAfter().signum() < 0 || (settled.reopens() && !queueLocked)) {
      return refuse(order, attempt.id());
    }
    if (bank != null) {
      return unchanged(
          order,
          Optional.of(
              linked.submit(order, attempt.id(), attempt.actor(), attempt.terms(), settled, bank)));
    }
    return writer.post(order, settled.source(), settled.destination(), effect(attempt, settled));
  }

  private static JobOrderMoneySettlementWriter.Effect effect(
      Attempt attempt, JobOrderMoneySettlementBasis.Resolved settled) {
    var terms = attempt.terms();
    return new JobOrderMoneySettlementWriter.Effect(
        attempt.id(),
        attempt.actor(),
        attempt.actor(),
        terms.kind(),
        terms.recordedSelection(),
        terms.requesterRecipientId(),
        settled.payer().accountId(),
        terms.amount(),
        terms.provision(),
        terms.hasFee() ? settled.rate() : null,
        settled.fee(),
        null,
        settled.reopens());
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> replay(
      JobOrder order, Attempt attempt, JobOrderMoneyOperation fact) {
    if (!attempt.actor().equals(fact.getActorId())) {
      throw new AccessDeniedException("Caller may not replay this operation.");
    }
    if (attempt.dto().bank() != null
        || fact.isBankHandling()
        || !attempt.terms().matches(fact, attempt.rate())) {
      throw new BusinessConflictException("orders.money.operation.termsMismatch");
    }
    var current = ledger.read(order.getId());
    var holding = current.holder(fact.personalHolderId());
    UUID designation = fact.getRecipientId();
    UUID payer =
        designation == null
            ? holding.getUserId()
            : recipients.accountFor(order.getId(), designation);
    basis.authorize(order, fact.getKind(), attempt.actor(), payer);
    return unchanged(order, Optional.of(queries.projectOutcome(fact, holding)));
  }

  private Accounts accounts(UUID orderId, JobOrderMoneySettlementTerms terms) {
    UUID sourceId = terms.sourceId();
    UUID requester = terms.requesterRecipientId();
    var selection = terms.destination();
    UUID destination =
        switch (selection.kind()) {
          case LINKED -> selection.reference();
          case EXISTING ->
              holders
                  .accountFor(orderId, Objects.requireNonNull(selection.reference()))
                  .orElse(null);
          case ACCOUNTLESS, SELF -> null;
        };
    return new Accounts(
        sourceId == null ? null : holders.accountFor(orderId, sourceId).orElse(null),
        destination,
        requester == null ? null : recipients.accountFor(orderId, requester));
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> refuse(JobOrder order, UUID id) {
    operations.reserveRefusal(order.getId(), id);
    return unchanged(order, Optional.empty());
  }

  private static JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> unchanged(
      JobOrder order, Optional<JobOrderMoneyOutcome> outcome) {
    return new JobOrderEditor.MoneyEdit<>(outcome, JobOrderEdit.unchanged(order));
  }

  private record Accounts(
      @Nullable UUID source, @Nullable UUID destination, @Nullable UUID requester) {
    List<UUID> linked() {
      return Stream.of(source, destination, requester).filter(Objects::nonNull).toList();
    }
  }

  private record Attempt(
      UUID id,
      UUID actor,
      SettleJobOrderMoneyDto dto,
      JobOrderMoneySettlementTerms terms,
      RateSnapshot rate,
      Accounts accounts) {}
}
