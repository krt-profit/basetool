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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Payment;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.PreviousAttempt;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Replaces a full failed Bank attempt without repeating its payment. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderLinkedMoneyRepair {
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final JobOrderMoneyIdentityLock identity;
  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderLinkedMoneyCommands commands;
  private final JobOrderMoneyQueries queries;
  private final JobOrderLinkedPaymentTerms fees;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderLinkedMoneyQueueImpact queueImpact;
  private final JobOrderLinkedMoneyIndependentRepair independent;
  private final JobOrderMoneyAccountHoldingRepository accountHoldings;

  /**
   * Admits a corrected request for a durable failed operation or recovers its identical retry.
   *
   * @param orderId the order under current authority
   * @param operationId the existing logical operation
   * @param dto the failed revision and corrected terms
   * @return the new attempt's authoritative outcome
   */
  public JobOrderMoneyOutcome replace(
      UUID orderId, UUID operationId, ReplaceJobOrderBankAttemptDto dto) {
    boolean outsideBank = Boolean.TRUE.equals(dto.outsideBank());
    if (outsideBank == (dto.bank() != null)) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    if (dto.bank() != null) {
      JobOrderLinkedPaymentTerms.validate(dto.bank());
    }
    BigDecimal amount = WholeAuec.positive(dto.amount());
    UUID actor = access.actorId();
    UUID observed =
        attempts
            .latestAttemptId(orderId, operationId)
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    var accounts = new HashSet<>(attempts.identityAccounts(observed));
    identity.lockAffectedAccounts(actor, accounts);
    var impact = attempts.impactOf(observed).orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    return editor.editMoney(
        orderId,
        order -> queueImpact.posting(order, impact.getKind(), amount),
        (order, locked) -> {
          if (!locked && queueImpact.posting(order, impact.getKind(), amount)) {
            throw JobOrderLinkedMoneyAttempt.conflict();
          }
          return replaceGuarded(order, operationId, dto, amount, actor, accounts);
        });
  }

  private JobOrderEditor.MoneyEdit<JobOrderMoneyOutcome> replaceGuarded(
      JobOrder order,
      UUID operationId,
      ReplaceJobOrderBankAttemptDto dto,
      BigDecimal amount,
      UUID actor,
      Set<UUID> accounts) {
    access.requireActiveActor();
    UUID latestId =
        attempts
            .latestAttemptId(order.getId(), operationId)
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    var prior = attempts.findById(latestId).orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    if (!accounts.equals(new HashSet<>(attempts.identityAccounts(prior.getId())))) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    if (!mayRepair(order, prior, actor)) {
      throw new AccessDeniedException("Caller may not replace this operation.");
    }
    BigDecimal provision = provisionOf(prior, dto, amount);
    if (Boolean.TRUE.equals(dto.outsideBank())) {
      return independent.repair(order, prior, dto.revision(), amount, provision, actor);
    }
    JobOrderBankSelection bank = Objects.requireNonNull(dto.bank());
    if (prior.getRevision() > dto.revision()
        && prior.getRevision() - dto.revision() == 1
        && prior.isActive()
        && actor.equals(prior.getActorId())
        && prior.matchesBank(bank)
        && prior.getAmount().compareTo(amount) == 0
        && prior.getProvision().compareTo(provision) == 0) {
      return result(order, prior);
    }
    if (prior.getRevision() != dto.revision()
        || !prior.replaceable()
        || order.getStatus() == JobOrderStatus.REJECTED
        || (!order.getStatus().isActive() && !prior.isActualPayment())) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var next = createReplacement(order, prior, bank, amount, provision, actor);
    return result(order, next);
  }

  private boolean mayRepair(JobOrder order, JobOrderLinkedMoneyAttempt attempt, UUID actor) {
    if (access.responsible(order)) {
      return true;
    }
    if (!actor.equals(attempt.getActorId())) {
      return false;
    }
    if (attempt.getKind().delivers() && access.requester(order)) {
      return true;
    }
    UUID holder = attempt.getHolderId();
    return holder == null
        ? actor.equals(attempt.getCounterpartyUserId())
        : actor.equals(ledger.read(order.getId()).holder(holder).getUserId());
  }

  private static BigDecimal provisionOf(
      JobOrderLinkedMoneyAttempt prior, ReplaceJobOrderBankAttemptDto dto, BigDecimal amount) {
    if (prior.getKind() == JobOrderMoneyKind.PROVISION) {
      if (dto.provision() != null) {
        throw JobOrderLinkedPaymentTerms.invalid();
      }
      return amount;
    }
    return dto.provision() == null ? prior.getProvision() : WholeAuec.nonnegative(dto.provision());
  }

  private JobOrderLinkedMoneyAttempt createReplacement(
      JobOrder order,
      JobOrderLinkedMoneyAttempt prior,
      JobOrderBankSelection bank,
      BigDecimal amount,
      BigDecimal provision,
      UUID actor) {
    var rate = prior.rateSnapshot();
    var fee = fees.feeFor(prior.getKind(), bank, amount, rate);
    validateLineage(order, prior, bank, fee.funding());
    BigDecimal binding = replacementBinding(order, prior, amount, provision, fee.orderFee());
    UUID accountHolding = accountPosition(order, prior, bank);
    var terms =
        new JobOrderLinkedMoneyAttempt.Terms(
            prior.getKind(),
            prior.getHolderId(),
            prior.getDestinationHolderId(),
            prior.getRecipientId(),
            prior.getCommitmentId(),
            accountHolding == null ? prior.selection() : null,
            amount,
            provision,
            rate,
            fee.amount(),
            fee.funding(),
            binding,
            prior.isActualPayment(),
            accountHolding);
    prior.supersede();
    attempts.flush();
    var next =
        attempts.saveAndFlush(
            new JobOrderLinkedMoneyAttempt(
                order.getId(),
                prior.getOperationId(),
                actor,
                terms,
                bank,
                prior.counterparty(),
                prior));
    commands
        .port()
        .replace(
            new Payment(
                next.getId(),
                next.getPurposeRef(),
                bank,
                amount,
                rate,
                prior.isActualPayment(),
                prior.counterparty()),
            new PreviousAttempt(prior.getId(), Objects.requireNonNull(prior.getRequestId())));
    if (next.getState() == JobOrderLinkedMoneyState.SUBMITTING) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    return next;
  }

  private void validateLineage(
      JobOrder order,
      JobOrderLinkedMoneyAttempt prior,
      JobOrderBankSelection bank,
      JobOrderMoneyFeeFunding funding) {
    if (prior.getBankType() != bank.type()
        || (prior.getAccountHoldingId() != null) != Boolean.TRUE.equals(bank.accountOnly())
        || !Objects.equals(prior.getBankCustodianId(), bank.custodianId())
        || !Objects.equals(prior.getBankTargetCustodianId(), bank.targetCustodianId())
        || prior.isActualPayment() != JobOrderLinkedPaymentTerms.reported(bank)
        || prior.getFeeFunding() != funding) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    if (bank.type() == JobOrderBankSelection.Type.DEPOSIT
        && prior.getKind() == JobOrderMoneyKind.PROVISION
        && !Objects.equals(prior.counterparty(), JobOrderLinkedPaymentTerms.depositor(bank))) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    UUID recipientId = prior.getRecipientId();
    if (recipientId != null) {
      UUID destination = recipients.require(order.getId(), recipientId).getBankAccountId();
      UUID selected = prior.getKind().delivers() ? bank.targetAccountId() : bank.accountId();
      if (destination != null && !destination.equals(selected)) {
        throw JobOrderLinkedPaymentTerms.invalid();
      }
    }
  }

  private @Nullable UUID accountPosition(
      JobOrder order, JobOrderLinkedMoneyAttempt prior, JobOrderBankSelection bank) {
    UUID previousPosition = prior.getAccountHoldingId();
    if (previousPosition == null) {
      return null;
    }
    UUID accountId = Objects.requireNonNull(bank.accountId());
    if (prior.getKind() == JobOrderMoneyKind.PROVISION) {
      var position =
          accountHoldings
              .findByOrderIdAndBankAccountId(order.getId(), accountId)
              .orElseGet(() ->
                  accountHoldings.saveAndFlush(
                      new JobOrderMoneyAccountHolding(order.getId(), accountId)));
      return Objects.requireNonNull(position.getId());
    }
    if (!accountId.equals(prior.getBankAccountId())) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    return previousPosition;
  }

  private BigDecimal replacementBinding(
      JobOrder order,
      JobOrderLinkedMoneyAttempt prior,
      BigDecimal amount,
      BigDecimal provision,
      BigDecimal fee) {
    if (prior.getKind() == JobOrderMoneyKind.PROVISION) {
      return BigDecimal.ZERO;
    }
    var current = ledger.read(order.getId());
    UUID account = prior.getAccountHoldingId();
    BigDecimal available =
        account == null
            ? current.availableBalance(prior.personalHolderId(), prior.getId())
            : current.availableAccountBalance(account, prior.getId());
    boolean returning = prior.getKind() == JobOrderMoneyKind.REQUESTER_RETURN;
    BigDecimal principal = returning ? BigDecimal.ZERO : amount;
    BigDecimal shortage = principal.subtract(available).max(BigDecimal.ZERO);
    if (provision.compareTo(shortage.add(fee)) > 0
        || (prior.getKind() == JobOrderMoneyKind.SETTLEMENT && shortage.signum() > 0)
        || (returning
            && current
                    .availableForRecipient(
                        Objects.requireNonNull(prior.getRecipientId()), prior.getId())
                    .compareTo(amount)
                < 0)) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    BigDecimal binding = principal.add(fee).subtract(provision).max(BigDecimal.ZERO);
    if (available.compareTo(binding) < 0) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    return binding;
  }

  private JobOrderEditor.MoneyEdit<JobOrderMoneyOutcome> result(
      JobOrder order, JobOrderLinkedMoneyAttempt attempt) {
    return new JobOrderEditor.MoneyEdit<>(
        queries.projectLinkedOutcome(attempt), JobOrderEdit.unchanged(order));
  }
}
