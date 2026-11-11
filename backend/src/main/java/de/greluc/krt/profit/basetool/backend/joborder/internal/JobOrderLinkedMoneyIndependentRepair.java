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
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Records an independently paid operation whose failed Bank request was unnecessary. */
@Component
@RequiredArgsConstructor
class JobOrderLinkedMoneyIndependentRepair {
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyProvisionWriter provisions;
  private final JobOrderMoneyMovementWriter movements;
  private final JobOrderLinkedMoneyQueueImpact queueImpact;
  private final JobOrderMoneyQueries queries;
  private final JobOrderEditor editor;
  private final TransferFeePolicy feePolicy;

  JobOrderEditor.MoneyEdit<JobOrderMoneyOutcome> repair(
      JobOrder order,
      JobOrderLinkedMoneyAttempt prior,
      long revision,
      BigDecimal amount,
      BigDecimal provision,
      UUID actor) {
    if (prior.getRevision() != revision
        || prior.getAmount().compareTo(amount) != 0
        || prior.getProvision().compareTo(provision) != 0
        || !prior.isActualPayment()
        || order.getStatus() == JobOrderStatus.REJECTED) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    requireIndependentFacts(order, prior);
    var booked = ledger.read(order.getId());
    var source = booked.holder(prior.personalHolderId());
    if (!prior.isActive()) {
      var fact =
          operations
              .findByOrderIdAndOperationId(order.getId(), prior.getOperationId())
              .filter(operation -> !operation.isBankHandling())
              .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
      if (!actor.equals(fact.getActorId())) {
        throw new AccessDeniedException("Caller may not replay this operation.");
      }
      return new JobOrderEditor.MoneyEdit<>(
          queries.projectOutcome(fact, source), JobOrderEdit.unchanged(order));
    }
    if (!prior.replaceable()) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    prior.supersede();
    var effect =
        prior.getKind() == JobOrderMoneyKind.PROVISION
            ? provision(order, prior, source, actor)
            : movement(order, prior, booked, source, actor);
    editor.recordOutcome(
        JobOrderEdit.audited(
            order,
            AuditEventType.JOB_ORDER_MONEY_BANK_DECIDED,
            AuditDetails.of("operationId", prior.getOperationId())
                .with("attemptId", prior.getId())
                .with("recordedOutsideBank", true)));
    return effect;
  }

  private void requireIndependentFacts(JobOrder order, JobOrderLinkedMoneyAttempt attempt) {
    if (attempt.getAccountHoldingId() != null) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    var type = attempt.getBankType();
    var kind = attempt.getKind();
    if (kind == JobOrderMoneyKind.PROVISION) {
      if (type != JobOrderBankSelection.Type.DEPOSIT
          || attempt.getFeeFunding() != JobOrderMoneyFeeFunding.DEPOSITOR) {
        throw JobOrderLinkedPaymentTerms.invalid();
      }
      return;
    }
    if ((kind != JobOrderMoneyKind.COLLECTION && kind != JobOrderMoneyKind.HANDOVER)
        || (type != JobOrderBankSelection.Type.DEPOSIT
            && type != JobOrderBankSelection.Type.WITHDRAWAL)
        || attempt.getProvision().signum() != 0) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    UUID recipientId = attempt.getRecipientId();
    if (recipientId != null) {
      var recipient = recipients.require(order.getId(), recipientId);
      if (recipient.getBankAccountId() != null
          || recipient.isSuperseded()
          || !recipients.party(recipient).usable()) {
        throw JobOrderLinkedPaymentTerms.invalid();
      }
    }
  }

  private JobOrderEditor.MoneyEdit<JobOrderMoneyOutcome> provision(
      JobOrder order, JobOrderLinkedMoneyAttempt attempt, JobOrderMoneyHolder holder, UUID actor) {
    var terms =
        new JobOrderMoneyProvisionWriter.Terms(
            attempt.getOperationId(),
            actor,
            actor,
            attempt.selection(),
            attempt.getCommitmentId(),
            false,
            attempt.getAmount(),
            false,
            null);
    var posted = provisions.postRecovered(order, holder, terms);
    return new JobOrderEditor.MoneyEdit<>(
        queries.projectOutcome(posted.operation(), holder), posted.edit());
  }

  private JobOrderEditor.MoneyEdit<JobOrderMoneyOutcome> movement(
      JobOrder order,
      JobOrderLinkedMoneyAttempt attempt,
      JobOrderMoneyLedger.Ledger booked,
      JobOrderMoneyHolder source,
      UUID actor) {
    BigDecimal fee = feePolicy.feeOn(attempt.getAmount(), attempt.rateSnapshot());
    if (booked
            .availableBalance(attempt.getHolderId(), attempt.getId())
            .compareTo(attempt.getAmount().add(fee))
        < 0) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    UUID destinationId = attempt.getDestinationHolderId();
    var destination = destinationId == null ? null : booked.holder(destinationId);
    UUID recipientId = attempt.getRecipientId();
    UUID receiver =
        destination == null
            ? recipients.accountFor(order.getId(), Objects.requireNonNull(recipientId))
            : destination.getUserId();
    var movement =
        new JobOrderMoneyMovementTerms(
            attempt.getKind(),
            attempt.personalHolderId(),
            attempt.selection(),
            recipientId,
            attempt.getAmount(),
            BigDecimal.ZERO,
            null);
    var terms =
        new JobOrderMoneyMovementWriter.Terms(
            attempt.getOperationId(),
            actor,
            actor,
            movement,
            receiver,
            attempt.getRate(),
            fee,
            queueImpact.posting(order, attempt.getKind(), attempt.getAmount()),
            null);
    var posted = movements.post(order, source, destination, terms);
    return new JobOrderEditor.MoneyEdit<>(
        queries.projectOutcome(posted.fact(), source), posted.edit());
  }
}
