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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Counterparty;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Admits account-only provision and handover against the order's own Bank account positions. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyAccountCommands {
  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyIdentityLock identities;
  private final JobOrderMoneyAccountHoldingRepository holdings;
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderLinkedMoneyCommands linked;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyCommitmentCredit commitmentCredit;
  private final JobOrderLinkedMoneyQueueImpact queueImpact;
  private final JobOrderLinkedPaymentTerms paymentTerms;
  private final TransferFeePolicy fees;

  Optional<JobOrderMoneyOutcome> provide(
      UUID orderId, ProvideJobOrderMoneyDto dto, JobOrderBankSelection bank) {
    validateProvision(dto.bankHandling(), dto.selection(), bank);
    UUID actor = access.actorId();
    identities.lockAccounts(actor, bank.counterpartyUserId());
    BigDecimal amount = WholeAuec.positive(dto.amount());
    return editor.editMoney(orderId, order -> provideGuarded(order, dto, bank, actor, amount));
  }

  JobOrderMoneyProvisionPreview previewProvision(
      JobOrder order, PreviewJobOrderMoneyProvisionDto dto, JobOrderBankSelection bank) {
    validateProvision(dto.bankHandling(), dto.selection(), bank);
    authorizeProvision(order, bank, access.requireActiveActor(), dto.commitmentId());
    if (dto.commitmentId() != null) {
      commitmentCredit.assertCurrentTarget(order, dto.commitmentId());
    }
    BigDecimal amount = WholeAuec.positive(dto.amount());
    var basis = provisionBasis(order.getId(), bank, dto.holderVersion());
    BigDecimal current =
        basis
            .position()
            .map(position ->
                ledger.read(order.getId()).accountBalance(Objects.requireNonNull(position.getId())))
            .orElse(BigDecimal.ZERO);
    var quote = linked.provisionQuote(bank, amount, provisionParty(bank));
    return new JobOrderMoneyProvisionPreview(
        amount.toPlainString(),
        null,
        "0",
        true,
        current.add(amount).toPlainString(),
        basis.revision(),
        quote);
  }

  private static void validateProvision(
      boolean bankHandling, JobOrderMoneySelection selection, JobOrderBankSelection bank) {
    JobOrderLinkedPaymentTerms.validate(bank);
    if (!bankHandling
        || selection.kind() != JobOrderMoneySelection.Kind.SELF
        || (bank.type() != JobOrderBankSelection.Type.DEPOSIT
            && bank.type() != JobOrderBankSelection.Type.AUTHORISATION)) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
  }

  private void authorizeProvision(
      JobOrder order, JobOrderBankSelection bank, UUID actor, @Nullable UUID commitmentId) {
    if (!access.responsible(order)
        && !(bank.type() == JobOrderBankSelection.Type.DEPOSIT
            && actor.equals(bank.counterpartyUserId())
            && commitmentId == null)) {
      throw new AccessDeniedException(
          "Caller may not provide funds on this account for the order.");
    }
    if (commitmentId != null) {
      commitmentCredit.assertAttributionAuthority(order, commitmentId);
    }
  }

  private static @Nullable Counterparty provisionParty(JobOrderBankSelection bank) {
    return bank.type() == JobOrderBankSelection.Type.DEPOSIT
        ? JobOrderLinkedPaymentTerms.depositor(bank)
        : null;
  }

  private ProvisionBasis provisionBasis(
      UUID orderId, JobOrderBankSelection bank, @Nullable Long version) {
    var position =
        holdings.findByOrderIdAndBankAccountId(orderId, Objects.requireNonNull(bank.accountId()));
    long revision = position.map(JobOrderMoneyAccountHolding::getRevision).orElse(0L);
    OptimisticLock.checkOptionalClient(
        revision,
        version,
        JobOrderMoneyAccountHolding.class,
        position.map(JobOrderMoneyAccountHolding::getId).orElse(null));
    return new ProvisionBasis(position, revision);
  }

  /** The existing collection position and observed Order-local revision. */
  private record ProvisionBasis(Optional<JobOrderMoneyAccountHolding> position, long revision) {}

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> provideGuarded(
      JobOrder order,
      ProvideJobOrderMoneyDto dto,
      JobOrderBankSelection bank,
      UUID actor,
      BigDecimal amount) {
    access.requireActiveActor();
    UUID commitmentId = dto.commitmentId();
    authorizeProvision(order, bank, actor, commitmentId);
    var previous =
        attempts.findByOrderIdAndOperationIdAndActiveTrue(order.getId(), dto.operationId());
    if (previous.isPresent()) {
      var attempt = previous.orElseThrow();
      if (!JobOrderLinkedMoneyCommands.matchesProvisionRate(dto.rate(), attempt.rateSnapshot())) {
        throw new BusinessConflictException("orders.money.operation.termsMismatch");
      }
      return replay(
          order,
          attempt,
          bank,
          new Expected(JobOrderMoneyKind.PROVISION, actor, amount, null, null, commitmentId));
    }
    assertFresh(order, dto.operationId());
    if (commitmentId != null) {
      commitmentCredit.assertCurrentTarget(order, commitmentId);
    }
    Counterparty depositor = provisionParty(bank);
    UUID accountId = Objects.requireNonNull(bank.accountId());
    var rate = linked.provisionRate(dto.rate());
    var basis = provisionBasis(order.getId(), bank, dto.holderVersion());
    var position =
        basis
            .position()
            .orElseGet(() ->
                holdings.saveAndFlush(new JobOrderMoneyAccountHolding(order.getId(), accountId)));
    var fee = paymentTerms.feeFor(JobOrderMoneyKind.PROVISION, bank, amount, rate);
    var terms =
        new JobOrderLinkedMoneyAttempt.Terms(
            JobOrderMoneyKind.PROVISION,
            null,
            null,
            null,
            commitmentId,
            null,
            amount,
            amount,
            rate,
            fee.amount(),
            fee.funding(),
            BigDecimal.ZERO,
            JobOrderLinkedPaymentTerms.reported(bank),
            Objects.requireNonNull(position.getId()));
    return accepted(
        order, linked.submitPrepared(order, dto.operationId(), actor, terms, bank, depositor));
  }

  Optional<JobOrderMoneyOutcome> move(
      UUID orderId, MoveJobOrderMoneyDto dto, JobOrderBankSelection bank) {
    UUID operationId = dto.operationId();
    if (operationId == null) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    var request = movement(dto, bank);
    UUID actor = access.actorId();
    identities.lockAccounts(actor);
    return editor.editMoney(
        orderId,
        order -> queueImpact.posting(order, JobOrderMoneyKind.PURPOSE_HANDOVER, request.amount()),
        (order, queueLocked) -> moveGuarded(order, operationId, request, actor, queueLocked));
  }

  JobOrderMoneyMovementPreview preview(
      UUID orderId, MoveJobOrderMoneyDto dto, JobOrderBankSelection bank) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    var request = movement(dto, bank);
    access.requireActiveActor();
    access.requireUnitWriter(order);
    var current = basis(order, request, coordinates(orderId, request));
    BigDecimal remaining = current.available().subtract(request.amount());
    BigDecimal overpayment =
        current
            .delivered()
            .add(request.amount())
            .subtract(current.target().getAmount())
            .max(BigDecimal.ZERO);
    return new JobOrderMoneyMovementPreview(
        request.amount().toPlainString(),
        "0",
        "0",
        "0",
        remaining.max(BigDecimal.ZERO).toPlainString(),
        null,
        null,
        overpayment.toPlainString(),
        remaining.negate().max(BigDecimal.ZERO).toPlainString(),
        null,
        current.delivered().add(request.amount()).compareTo(current.target().getAmount()) >= 0,
        false,
        linked.previewAccountTransfer(current.bank(), request.amount()));
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> moveGuarded(
      JobOrder order, UUID operationId, Movement request, UUID actor, boolean queueLocked) {
    access.requireActiveActor();
    access.requireUnitWriter(order);
    var coordinates = coordinates(order.getId(), request);
    var previous = attempts.findByOrderIdAndOperationIdAndActiveTrue(order.getId(), operationId);
    if (previous.isPresent()) {
      return replay(
          order,
          previous.orElseThrow(),
          coordinates.bank(),
          new Expected(
              JobOrderMoneyKind.PURPOSE_HANDOVER,
              actor,
              request.amount(),
              request.sourceId(),
              request.recipientId(),
              null));
    }
    assertFresh(order, operationId);
    var current = basis(order, request, coordinates);
    if (current.available().compareTo(request.amount()) < 0
        || (!queueLocked
            && queueImpact.posting(order, JobOrderMoneyKind.PURPOSE_HANDOVER, request.amount()))) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var terms =
        new JobOrderLinkedMoneyAttempt.Terms(
            JobOrderMoneyKind.PURPOSE_HANDOVER,
            null,
            null,
            request.recipientId(),
            null,
            null,
            request.amount(),
            BigDecimal.ZERO,
            fees.snapshot(),
            BigDecimal.ZERO,
            JobOrderMoneyFeeFunding.NONE,
            request.amount(),
            false,
            request.sourceId());
    return accepted(
        order, linked.submitPrepared(order, operationId, actor, terms, coordinates.bank(), null));
  }

  private Coordinates coordinates(UUID orderId, Movement request) {
    var position =
        holdings
            .findByOrderIdAndId(orderId, request.sourceId())
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    var designation = recipients.require(orderId, request.recipientId());
    return new Coordinates(
        position,
        designation,
        JobOrderLinkedPaymentTerms.resolveAccountTransfer(
            request.bank(), position.getBankAccountId(), designation.getBankAccountId()));
  }

  private Basis basis(JobOrder order, Movement request, Coordinates coordinates) {
    if (!order.getStatus().isActive() || queries.responsibilityInvalid(order)) {
      throw new BusinessConflictException("orders.money.movement.inactive");
    }
    UUID orderId = order.getId();
    var position = coordinates.position();
    var designation = coordinates.designation();
    if (!designation.isActive() || designation.isSuperseded()) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    OptimisticLock.check(
        position.getRevision(),
        request.sourceVersion(),
        JobOrderMoneyAccountHolding.class,
        request.sourceId());
    var target = targets.findById(orderId).orElseThrow();
    OptimisticLock.check(
        target.getVersion(), request.targetVersion(), JobOrderMoneyTarget.class, orderId);
    var current = ledger.read(orderId);
    return new Basis(
        current.availableAccountBalance(request.sourceId()),
        current.handedOver(),
        target,
        coordinates.bank());
  }

  private void assertFresh(JobOrder order, UUID operationId) {
    if (!order.getStatus().isActive()
        || operations.isRefused(order.getId(), operationId)
        || operations.existsByOrderIdAndOperationId(order.getId(), operationId)) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> replay(
      JobOrder order,
      JobOrderLinkedMoneyAttempt previous,
      JobOrderBankSelection bank,
      Expected expected) {
    if (!expected.actor().equals(previous.getActorId())) {
      throw new AccessDeniedException("Caller may not replay this operation.");
    }
    if (!previous.matchesBank(bank)
        || previous.getKind() != expected.kind()
        || previous.getAmount().compareTo(expected.amount()) != 0
        || (expected.sourceId() != null
            && !expected.sourceId().equals(previous.getAccountHoldingId()))
        || !Objects.equals(previous.getRecipientId(), expected.recipientId())
        || !Objects.equals(previous.getCommitmentId(), expected.commitmentId())) {
      throw new BusinessConflictException("orders.money.operation.termsMismatch");
    }
    return accepted(order, queries.projectLinkedOutcome(previous));
  }

  private static JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> accepted(
      JobOrder order, JobOrderMoneyOutcome outcome) {
    return new JobOrderEditor.MoneyEdit<>(Optional.of(outcome), JobOrderEdit.unchanged(order));
  }

  private static Movement movement(MoveJobOrderMoneyDto dto, JobOrderBankSelection bank) {
    UUID source = dto.sourceAccountHoldingId();
    UUID recipient = dto.recipientId();
    if (!dto.bankHandling()
        || bank.type() != JobOrderBankSelection.Type.TRANSFER
        || dto.kind() != JobOrderMoneyKind.PURPOSE_HANDOVER
        || source == null
        || dto.sourceHolderId() != null
        || recipient == null
        || dto.destinationHolderId() != null
        || dto.destinationUserId() != null
        || dto.destinationName() != null
        || dto.destinationVersion() != null
        || dto.commitmentId() != null
        || (dto.provision() != null && WholeAuec.nonnegative(dto.provision()).signum() != 0)
        || (dto.rate() != null
            && JobOrderMoneyMovementTerms.submittedRate(dto.rate()).rate().signum() != 0)) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    return new Movement(
        source,
        recipient,
        dto.sourceVersion(),
        dto.targetVersion(),
        WholeAuec.positive(dto.amount()),
        bank);
  }

  /** The submitted movement and scoped client observations. */
  private record Movement(
      UUID sourceId,
      UUID recipientId,
      @Nullable Long sourceVersion,
      @Nullable Long targetVersion,
      BigDecimal amount,
      JobOrderBankSelection bank) {}

  /** The scoped positions and their corresponding Bank coordinates. */
  private record Coordinates(
      JobOrderMoneyAccountHolding position,
      JobOrderMoneyRecipient designation,
      JobOrderBankSelection bank) {}

  /** Current account availability and the fulfilment basis for the target. */
  private record Basis(
      BigDecimal available,
      BigDecimal delivered,
      JobOrderMoneyTarget target,
      JobOrderBankSelection bank) {}

  /** Frozen order-side terms expected in a recovered result. */
  private record Expected(
      JobOrderMoneyKind kind,
      UUID actor,
      BigDecimal amount,
      @Nullable UUID sourceId,
      @Nullable UUID recipientId,
      @Nullable UUID commitmentId) {}
}
