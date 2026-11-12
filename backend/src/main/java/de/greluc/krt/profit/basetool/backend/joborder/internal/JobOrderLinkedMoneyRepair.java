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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Counterparty;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Payment;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.PreviousAttempt;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Terms;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
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
    if ((outsideBank && dto.bank() != null)
        || ((outsideBank || dto.bank() != null)
            && (dto.accountId() != null || dto.justification() != null))) {
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
    if (!access.mayRepair(order, prior, actor, ledger.read(order.getId()))) {
      throw new AccessDeniedException("Caller may not replace this operation.");
    }
    if (Boolean.TRUE.equals(dto.outsideBank())) {
      return independent.repair(
          order, prior, dto.revision(), amount, provisionOf(prior, dto, amount), actor);
    }
    if (prior.getRevision() != dto.revision()) {
      return replayReplacement(order, prior, dto, amount, actor);
    }
    if (!JobOrderMoneyAccess.repairable(order, prior)) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var bank = selection(order, prior, dto);
    var next =
        createReplacement(order, prior, bank, amount, provisionOf(prior, dto, amount), actor);
    return result(order, next);
  }

  private JobOrderEditor.MoneyEdit<JobOrderMoneyOutcome> replayReplacement(
      JobOrder order,
      JobOrderLinkedMoneyAttempt successor,
      ReplaceJobOrderBankAttemptDto dto,
      BigDecimal amount,
      UUID actor) {
    if (successor.getRevision() <= dto.revision()
        || successor.getRevision() - dto.revision() != 1
        || !successor.isActive()
        || !actor.equals(successor.getActorId())
        || successor.getAmount().compareTo(amount) != 0) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var predecessor =
        attempts
            .findByOrderIdAndOperationIdAndRevision(
                order.getId(), successor.getOperationId(), dto.revision())
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    if (!successor.matchesBank(selection(order, predecessor, dto))
        || successor.getProvision().compareTo(provisionOf(predecessor, dto, amount)) != 0) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    return result(order, successor);
  }

  /**
   * Reads an authorized repair without exposing the previous Bank request or custody.
   *
   * @param orderId the order
   * @param operationId the existing logical operation
   * @return the failed attempt and its currently permitted account options
   */
  @Transactional(readOnly = true)
  public JobOrderMoneyRepairView getRepair(UUID orderId, UUID operationId) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    var prior = requireRepairable(order, operationId);
    UUID accountId = prior.getBankAccountId();
    var bank = commands.port();
    return new JobOrderMoneyRepairView(
        prior.getRevision(),
        prior.getAmount().toPlainString(),
        prior.getProvision().toPlainString(),
        prior.getKind(),
        prior.getBankType(),
        prior.isActualPayment(),
        prior.getActualFee().toPlainString(),
        prior.getRate().toPlainString(),
        prior.getFeeFunding(),
        independent.permitsIndependentFacts(order, prior),
        accountChangeAllowed(prior),
        accountId != null && bank.canSeeAccount(accountId) ? accountId : null,
        repairAccounts(order, prior));
  }

  /**
   * Quotes corrected principal and binding against the failed attempt's retained fee basis.
   *
   * @param orderId the order
   * @param operationId the existing logical operation
   * @param dto the intended corrected terms
   * @return the exact principal, fee and order-source charge
   */
  @Transactional(readOnly = true)
  public JobOrderMoneyRepairQuote preview(
      UUID orderId, UUID operationId, ReplaceJobOrderBankAttemptDto dto) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    var prior = requireRepairable(order, operationId);
    OptimisticLock.checkRequired(
        prior.getRevision(), dto.revision(), JobOrderLinkedMoneyAttempt.class, prior.getId());
    BigDecimal amount = WholeAuec.positive(dto.amount());
    BigDecimal provision = provisionOf(prior, dto, amount);
    if (Boolean.TRUE.equals(dto.outsideBank())) {
      if (dto.bank() != null
          || dto.accountId() != null
          || dto.justification() != null
          || !independent.permitsIndependentFacts(order, prior)
          || prior.getAmount().compareTo(amount) != 0
          || prior.getProvision().compareTo(provision) != 0) {
        throw JobOrderLinkedPaymentTerms.invalid();
      }
      return quote(
          prior,
          amount,
          new JobOrderLinkedPaymentTerms.Fee(prior.getActualFee(), prior.getFeeFunding()),
          prior.getSourceBinding(),
          null);
    }
    JobOrderBankSelection bank = selection(order, prior, dto);
    var fee = fees.feeFor(prior.getKind(), bank, amount, prior.rateSnapshot());
    validateLineage(order, prior, bank, fee.funding());
    BigDecimal binding = replacementBinding(order, prior, amount, provision, fee.orderFee());
    var basis =
        commands
            .port()
            .quoteReplacement(
                new Terms(
                    bank,
                    amount,
                    prior.rateSnapshot(),
                    prior.isActualPayment(),
                    counterparty(prior)),
                prior.getPurposeRef(),
                previous(prior));
    var bankQuote =
        new JobOrderMoneyBankPreview(
            basis.pending()
                ? JobOrderMoneyBankPreview.Disposition.PENDING
                : JobOrderMoneyBankPreview.Disposition.DIRECT,
            fee.amount().toPlainString(),
            fee.funding(),
            basis.sourceDebit().toPlainString(),
            binding.toPlainString(),
            prior.getRate().toPlainString());
    return quote(prior, amount, fee, binding, bankQuote);
  }

  private JobOrderLinkedMoneyAttempt requireRepairable(JobOrder order, UUID operationId) {
    var prior =
        Entities.require(
            attempts.findByOrderIdAndOperationIdAndActiveTrue(order.getId(), operationId),
            "orders.money.operation.unknown");
    if (!access.mayRepair(order, prior, access.requireActiveActor(), ledger.read(order.getId()))) {
      throw new AccessDeniedException("Caller may not replace this operation.");
    }
    if (!JobOrderMoneyAccess.repairable(order, prior)) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    return prior;
  }

  private List<JobOrderMoneyRepairView.RepairAccount> repairAccounts(
      JobOrder order, JobOrderLinkedMoneyAttempt prior) {
    if (!accountChangeAllowed(prior)) {
      return List.of();
    }
    boolean deposit = prior.getBankType() == JobOrderBankSelection.Type.DEPOSIT;
    var options =
        commands
            .port()
            .accountOptions(
                order.getResponsibleOrgUnit().getId(),
                order.getRequestingOrgUnit().getId(),
                deposit,
                false,
                !deposit);
    return (deposit ? options.collection() : options.withdrawal())
        .stream()
            .map(account -> new JobOrderMoneyRepairView.RepairAccount(account.id(), account.name()))
            .toList();
  }

  private JobOrderBankSelection selection(
      JobOrder order, JobOrderLinkedMoneyAttempt prior, ReplaceJobOrderBankAttemptDto dto) {
    JobOrderBankSelection submitted = dto.bank();
    if (submitted != null) {
      if (dto.accountId() != null || dto.justification() != null) {
        throw JobOrderLinkedPaymentTerms.invalid();
      }
      JobOrderLinkedPaymentTerms.validate(submitted);
      return submitted;
    }
    UUID account = dto.accountId();
    if (account != null
        && (!accountChangeAllowed(prior) || !repairAccountPermitted(order, prior, account))) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    boolean deposit = prior.getBankType() == JobOrderBankSelection.Type.DEPOSIT;
    var depositor =
        deposit && prior.getKind() == JobOrderMoneyKind.PROVISION
            ? prior.depositCounterpartyForReplacement()
            : null;
    var retained =
        new JobOrderBankSelection(
            prior.getBankType(),
            prior.getAccountHoldingId() != null,
            account == null ? prior.getBankAccountId() : account,
            prior.getBankCustodianId(),
            prior.getBankTargetAccountId(),
            prior.getBankTargetCustodianId(),
            prior.isActualPayment(),
            (deposit && prior.getAccountHoldingId() == null)
                ? prior.getFeeFunding() == JobOrderMoneyFeeFunding.ORDER
                : null,
            depositor == null ? null : depositor.userId(),
            depositor == null ? null : depositor.name(),
            dto.justification());
    JobOrderLinkedPaymentTerms.validate(retained);
    return retained;
  }

  private boolean repairAccountPermitted(
      JobOrder order, JobOrderLinkedMoneyAttempt prior, UUID accountId) {
    if (commands.port().canSeeAccount(accountId)) {
      return true;
    }
    return prior.getBankType() == JobOrderBankSelection.Type.DEPOSIT
        && repairAccounts(order, prior).stream()
            .anyMatch(account -> accountId.equals(account.id()));
  }

  private static boolean accountChangeAllowed(JobOrderLinkedMoneyAttempt prior) {
    return prior.getBankAccountId() != null
        && prior.getKind() != JobOrderMoneyKind.REQUESTER_RETURN
        && (prior.getAccountHoldingId() == null || prior.getKind() == JobOrderMoneyKind.PROVISION);
  }

  private static JobOrderMoneyRepairQuote quote(
      JobOrderLinkedMoneyAttempt prior,
      BigDecimal amount,
      JobOrderLinkedPaymentTerms.Fee fee,
      BigDecimal binding,
      @Nullable JobOrderMoneyBankPreview bank) {
    return new JobOrderMoneyRepairQuote(
        prior.getRevision(),
        amount.toPlainString(),
        fee.amount().toPlainString(),
        fee.funding(),
        binding.toPlainString(),
        prior.getRate().toPlainString(),
        bank);
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
                counterparty(prior)),
            previous(prior));
    if (next.getState() == JobOrderLinkedMoneyState.SUBMITTING) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    return next;
  }

  private static PreviousAttempt previous(JobOrderLinkedMoneyAttempt prior) {
    return new PreviousAttempt(prior.getId(), Objects.requireNonNull(prior.getRequestId()));
  }

  private static @Nullable Counterparty counterparty(JobOrderLinkedMoneyAttempt prior) {
    return prior.getBankType() == JobOrderBankSelection.Type.DEPOSIT
            && prior.getKind() == JobOrderMoneyKind.PROVISION
        ? prior.depositCounterpartyForReplacement()
        : prior.counterparty();
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
        && !prior.matchesDepositor(bank)) {
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
    if (!accountId.equals(prior.getBankAccountId())
        || (prior.getKind() == JobOrderMoneyKind.REQUESTER_RETURN
            && !Objects.equals(prior.getBankTargetAccountId(), bank.targetAccountId()))) {
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
    if (returning) {
      UUID recipient = Objects.requireNonNull(prior.getRecipientId());
      BigDecimal receipt =
          account == null
              ? current.availableForPersonalReturn(recipient, prior.getId())
              : current.availableForAccountReturn(account, recipient, prior.getId());
      if (receipt.compareTo(amount) < 0) {
        throw JobOrderLinkedMoneyAttempt.conflict();
      }
    }
    if (provision.compareTo(shortage.add(fee)) > 0
        || (prior.getKind() == JobOrderMoneyKind.SETTLEMENT && shortage.signum() > 0)) {
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
