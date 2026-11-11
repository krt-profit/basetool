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

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Returns a confirmed requester-account receipt to its original collection position. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyAccountReturns {
  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneySettlementBasis settlement;
  private final JobOrderMoneyIdentityLock identities;
  private final JobOrderMoneyAccountHoldingRepository accounts;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyQueries queries;
  private final JobOrderLinkedMoneyQueueImpact queueImpact;
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderLinkedMoneyCommands linked;
  private final TransferFeePolicy fees;

  Optional<JobOrderMoneyOutcome> record(UUID orderId, SettleJobOrderMoneyDto dto) {
    var terms = terms(dto);
    UUID id = dto.operationId();
    if (id == null) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    UUID actor = access.actorId();
    identities.lockAccounts(actor);
    return editor.editMoney(
        orderId,
        order -> queueImpact.posting(order, JobOrderMoneyKind.REQUESTER_RETURN, terms.amount()),
        (order, queueLocked) -> recordGuarded(order, id, terms, actor, queueLocked));
  }

  JobOrderMoneyMovementPreview preview(UUID orderId, SettleJobOrderMoneyDto dto) {
    var terms = terms(dto);
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    authorize(order);
    var coordinates = coordinates(order.getId(), terms);
    var basis = basis(order, terms, coordinates);
    BigDecimal remaining = basis.availableReceipt().subtract(terms.amount());
    BigDecimal overpayment =
        basis.delivered().subtract(terms.amount()).subtract(basis.target()).max(BigDecimal.ZERO);
    return new JobOrderMoneyMovementPreview(
        terms.amount().toPlainString(),
        "0",
        "0",
        "0",
        remaining.max(BigDecimal.ZERO).toPlainString(),
        null,
        null,
        overpayment.toPlainString(),
        remaining.negate().max(BigDecimal.ZERO).toPlainString(),
        basis.collectionAvailable().add(terms.amount()).toPlainString(),
        false,
        order.getStatus() == JobOrderStatus.COMPLETED
            && basis.delivered().subtract(terms.amount()).compareTo(basis.target()) < 0,
        linked.previewAccountTransfer(coordinates.bank(), terms.amount()));
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> recordGuarded(
      JobOrder order, UUID operation, Terms terms, UUID actor, boolean queueLocked) {
    authorize(order);
    var coordinates = coordinates(order.getId(), terms);
    var previous = attempts.findByOrderIdAndOperationIdAndActiveTrue(order.getId(), operation);
    if (previous.isPresent()) {
      var attempt = previous.orElseThrow();
      if (!actor.equals(attempt.getActorId())) {
        throw new AccessDeniedException("Caller may not replay this operation.");
      }
      if (!attempt.matchesBank(coordinates.bank())
          || attempt.getKind() != JobOrderMoneyKind.REQUESTER_RETURN
          || !terms.recipientId().equals(attempt.getRecipientId())
          || !terms.accountHoldingId().equals(attempt.getAccountHoldingId())
          || attempt.getAmount().compareTo(terms.amount()) != 0) {
        throw new BusinessConflictException("orders.money.operation.termsMismatch");
      }
      return result(order, queries.projectLinkedOutcome(attempt));
    }
    if (operations.existsByOrderIdAndOperationId(order.getId(), operation)
        || operations.isRefused(order.getId(), operation)) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var basis = basis(order, terms, coordinates);
    if (basis.availableReceipt().compareTo(terms.amount()) < 0
        || (!queueLocked
            && queueImpact.posting(order, JobOrderMoneyKind.REQUESTER_RETURN, terms.amount()))) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var effect =
        new JobOrderLinkedMoneyAttempt.Terms(
            JobOrderMoneyKind.REQUESTER_RETURN,
            null,
            null,
            terms.recipientId(),
            null,
            null,
            terms.amount(),
            BigDecimal.ZERO,
            fees.snapshot(),
            BigDecimal.ZERO,
            JobOrderMoneyFeeFunding.NONE,
            BigDecimal.ZERO,
            false,
            terms.accountHoldingId());
    return result(
        order, linked.submitPrepared(order, operation, actor, effect, coordinates.bank(), null));
  }

  private Coordinates coordinates(UUID orderId, Terms terms) {
    var position =
        accounts
            .findByOrderIdAndId(orderId, terms.accountHoldingId())
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    var designation = recipients.require(orderId, terms.recipientId());
    return new Coordinates(
        position,
        designation,
        JobOrderLinkedPaymentTerms.resolveAccountTransfer(
            terms.bank(), designation.getBankAccountId(), position.getBankAccountId()));
  }

  private Basis basis(JobOrder order, Terms terms, Coordinates coordinates) {
    UUID orderId = order.getId();
    var position = coordinates.position();
    if (coordinates.designation().isSuperseded()) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    OptimisticLock.check(
        position.getRevision(),
        terms.destinationVersion(),
        JobOrderMoneyAccountHolding.class,
        terms.accountHoldingId());
    var target = targets.findById(orderId).orElseThrow();
    OptimisticLock.check(
        target.getVersion(), terms.targetVersion(), JobOrderMoneyTarget.class, orderId);
    var current = ledger.read(orderId);
    return new Basis(
        current.availableForAccountReturn(terms.accountHoldingId(), terms.recipientId(), null),
        current.availableAccountBalance(terms.accountHoldingId()),
        current.handedOver(),
        target.getAmount());
  }

  private void authorize(JobOrder order) {
    settlement.authorize(
        order, JobOrderMoneyKind.REQUESTER_RETURN, access.requireActiveActor(), null);
    if (queries.responsibilityInvalid(order) || order.getStatus() == JobOrderStatus.REJECTED) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }

  private static Terms terms(SettleJobOrderMoneyDto dto) {
    UUID position = dto.destinationAccountHoldingId();
    UUID recipient = dto.recipientId();
    var bank = dto.bank();
    if (position == null
        || recipient == null
        || bank == null
        || !dto.bankHandling()
        || !Boolean.TRUE.equals(bank.accountOnly())
        || bank.type() != JobOrderBankSelection.Type.TRANSFER
        || dto.kind() != JobOrderMoneyKind.REQUESTER_RETURN
        || dto.sourceHolderId() != null
        || dto.sourceAccountHoldingId() != null
        || dto.sourceVersion() != null
        || dto.destinationHolderId() != null
        || dto.destinationUserId() != null
        || dto.destinationName() != null
        || (dto.provision() != null && WholeAuec.nonnegative(dto.provision()).signum() != 0)
        || (dto.rate() != null
            && JobOrderMoneyMovementTerms.submittedRate(dto.rate()).rate().signum() != 0)) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    return new Terms(
        position,
        recipient,
        dto.destinationVersion(),
        dto.targetVersion(),
        WholeAuec.positive(dto.amount()),
        bank);
  }

  private static JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> result(
      JobOrder order, JobOrderMoneyOutcome outcome) {
    return new JobOrderEditor.MoneyEdit<>(Optional.of(outcome), JobOrderEdit.unchanged(order));
  }

  /** The immutable requester account and the collection position with its observed revision. */
  private record Terms(
      UUID accountHoldingId,
      UUID recipientId,
      @Nullable Long destinationVersion,
      @Nullable Long targetVersion,
      BigDecimal amount,
      JobOrderBankSelection bank) {}

  /** The scoped return positions and their corresponding Bank coordinates. */
  private record Coordinates(
      JobOrderMoneyAccountHolding position,
      JobOrderMoneyRecipient designation,
      JobOrderBankSelection bank) {}

  /** The guarded receipt and destination amounts used by a return. */
  private record Basis(
      BigDecimal availableReceipt,
      BigDecimal collectionAvailable,
      BigDecimal delivered,
      BigDecimal target) {}
}
