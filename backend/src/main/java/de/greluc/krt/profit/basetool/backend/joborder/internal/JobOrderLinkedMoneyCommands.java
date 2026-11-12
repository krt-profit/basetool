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
import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Counterparty;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Submits prepared order effects and recovers a known Bank-linked attempt under the order guard.
 */
@Component
@RequiredArgsConstructor
class JobOrderLinkedMoneyCommands {
  private final ObjectProvider<JobOrderBankPort> bankPort;
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyQueries queries;
  private final TransferFeePolicy fees;
  private final JobOrderLinkedPaymentTerms paymentTerms;

  Optional<JobOrderMoneyOutcome> replayProvision(
      JobOrder order,
      ProvideJobOrderMoneyDto dto,
      UUID actor,
      BigDecimal amount,
      JobOrderMoneySelection selection) {
    return attempts
        .findByOrderIdAndOperationIdAndActiveTrue(order.getId(), dto.operationId())
        .map(attempt -> {
          if (!actor.equals(attempt.getActorId())) {
            throw new AccessDeniedException("Caller may not replay this operation.");
          }
          JobOrderBankSelection bank = dto.bank();
          if (!dto.bankHandling()
              || bank == null
              || !attempt.matchesBank(bank)
              || attempt.getKind() != JobOrderMoneyKind.PROVISION
              || attempt.getAmount().compareTo(amount) != 0
              || !selection.equals(attempt.selection())
              || !Objects.equals(dto.commitmentId(), attempt.getCommitmentId())
              || !matchesProvisionRate(dto.rate(), attempt.rateSnapshot())) {
            throw new BusinessConflictException("orders.money.operation.termsMismatch");
          }
          return queries.projectLinkedOutcome(attempt);
        });
  }

  JobOrderMoneyOutcome submitProvision(
      JobOrder order,
      ProvideJobOrderMoneyDto dto,
      UUID actor,
      BigDecimal amount,
      JobOrderMoneySelection selection,
      JobOrderMoneyHolder holder,
      JobOrderBankSelection bank) {
    Counterparty counterparty = provisionParty(holder, bank);
    var rate = provisionRate(dto.rate());
    if (holder.getId() == null) {
      holder.recordChange();
      holders.saveAndFlush(holder);
    }
    var fee = paymentTerms.feeFor(JobOrderMoneyKind.PROVISION, bank, amount, rate);
    var terms =
        new JobOrderLinkedMoneyAttempt.Terms(
            JobOrderMoneyKind.PROVISION,
            Objects.requireNonNull(holder.getId()),
            null,
            null,
            dto.commitmentId(),
            selection,
            amount,
            amount,
            rate,
            fee.amount(),
            fee.funding(),
            BigDecimal.ZERO,
            JobOrderLinkedPaymentTerms.reported(bank));
    return submitPrepared(order, dto.operationId(), actor, terms, bank, counterparty);
  }

  JobOrderMoneyBankPreview previewProvision(
      JobOrderMoneyHolder holder, BigDecimal amount, JobOrderBankSelection bank) {
    return provisionQuote(bank, amount, provisionParty(holder, bank));
  }

  JobOrderMoneyBankPreview provisionQuote(
      JobOrderBankSelection bank, BigDecimal amount, @Nullable Counterparty counterparty) {
    var rate = fees.snapshot();
    var fee = paymentTerms.feeFor(JobOrderMoneyKind.PROVISION, bank, amount, rate);
    return quote(bank, amount, rate, fee, counterparty, BigDecimal.ZERO);
  }

  RateSnapshot provisionRate(@Nullable String observed) {
    var rate = fees.snapshot();
    if (!matchesProvisionRate(observed, rate)) {
      throw new BusinessConflictException("orders.money.movement.rateRequired");
    }
    return rate;
  }

  static boolean matchesProvisionRate(@Nullable String observed, RateSnapshot rate) {
    return observed == null || rate.equals(JobOrderMoneyMovementTerms.submittedRate(observed));
  }

  private @Nullable Counterparty provisionParty(
      JobOrderMoneyHolder holder, JobOrderBankSelection bank) {
    JobOrderLinkedPaymentTerms.validate(bank);
    var port = port();
    return switch (bank.type()) {
      case AUTHORISATION -> {
        requireCustodian(port, bank, holder);
        yield null;
      }
      case DEPOSIT -> {
        if (Boolean.TRUE.equals(bank.orderFundedDepositFee())) {
          throw JobOrderLinkedPaymentTerms.invalid();
        }
        requireCustodian(port, bank, holder);
        yield JobOrderLinkedPaymentTerms.depositor(bank);
      }
      case WITHDRAWAL -> {
        if (!JobOrderLinkedPaymentTerms.reported(bank)) {
          requireCustodian(port, bank, holder);
        }
        yield party(holder);
      }
      case TRANSFER, HOLDER_TRANSFER -> throw JobOrderLinkedPaymentTerms.invalid();
    };
  }

  JobOrderMoneyBankPreview quote(
      JobOrderBankSelection bank,
      BigDecimal amount,
      RateSnapshot rate,
      JobOrderLinkedPaymentTerms.Fee fee,
      @Nullable Counterparty counterparty,
      BigDecimal orderSourceCharge) {
    var quote =
        port()
            .quote(
                new JobOrderBankPort.Terms(
                    bank, amount, rate, JobOrderLinkedPaymentTerms.reported(bank), counterparty));
    return new JobOrderMoneyBankPreview(
        quote.pending()
            ? JobOrderMoneyBankPreview.Disposition.PENDING
            : JobOrderMoneyBankPreview.Disposition.DIRECT,
        fee.amount().toPlainString(),
        fee.funding(),
        quote.sourceDebit().toPlainString(),
        orderSourceCharge.toPlainString(),
        rate.rate().toPlainString());
  }

  Optional<JobOrderMoneyOutcome> replayMovement(
      JobOrder order,
      UUID operationId,
      @Nullable JobOrderBankSelection bank,
      UUID actor,
      JobOrderMoneyMovementTerms movement,
      TransferFeePolicy.RateSnapshot rate) {
    return attempts
        .findByOrderIdAndOperationIdAndActiveTrue(order.getId(), operationId)
        .map(attempt -> {
          if (!actor.equals(attempt.getActorId())) {
            throw new AccessDeniedException("Caller may not replay this operation.");
          }
          if (bank == null
              || !attempt.matchesBank(bank)
              || attempt.getKind() != movement.kind()
              || !movement.sourceId().equals(attempt.getHolderId())
              || !Objects.equals(attempt.getRecipientId(), movement.recipientId())
              || !Objects.equals(attempt.getCommitmentId(), movement.commitmentId())
              || attempt.getAmount().compareTo(movement.amount()) != 0
              || attempt.getProvision().compareTo(movement.provision()) != 0
              || !attempt.selection().equals(movement.selection())
              || !(movement.kind() == JobOrderMoneyKind.PURPOSE_HANDOVER
                  ? rate.rate().signum() == 0
                  : rate.equals(attempt.rateSnapshot()))) {
            throw new BusinessConflictException("orders.money.operation.termsMismatch");
          }
          return queries.projectLinkedOutcome(attempt);
        });
  }

  JobOrderMoneyBankPreview previewMovement(
      JobOrderMoneyMovementTerms movement,
      JobOrderMoneyMovementBasis.Resolved resolved,
      JobOrderBankSelection bank) {
    var counterparty =
        movementParty(
            port(),
            movement.kind(),
            bank,
            resolved.source(),
            resolved.destination(),
            resolved.recipient());
    var fee = Objects.requireNonNull(resolved.linkedFee());
    return quote(
        bank,
        movement.amount(),
        resolved.configuredRate(),
        fee,
        counterparty,
        movement.amount().add(fee.orderFee()));
  }

  JobOrderMoneyBankPreview previewAccountTransfer(JobOrderBankSelection bank, BigDecimal amount) {
    return quote(
        bank,
        amount,
        fees.snapshot(),
        new JobOrderLinkedPaymentTerms.Fee(BigDecimal.ZERO, JobOrderMoneyFeeFunding.NONE),
        null,
        amount);
  }

  JobOrderMoneyOutcome submitMovement(
      JobOrder order,
      UUID operationId,
      UUID actor,
      JobOrderMoneyMovementTerms movement,
      JobOrderMoneyMovementBasis.Resolved resolved,
      JobOrderBankSelection bank) {
    var port = port();
    var source = resolved.source();
    var destination = resolved.destination();
    Counterparty counterparty =
        movementParty(port, movement.kind(), bank, source, destination, resolved.recipient());
    var fee = Objects.requireNonNull(resolved.linkedFee());
    BigDecimal binding =
        movement.amount().add(fee.orderFee()).subtract(movement.provision()).max(BigDecimal.ZERO);
    if (destination != null && destination.getId() == null) {
      destination.recordChange();
      holders.saveAndFlush(destination);
    }
    var terms =
        new JobOrderLinkedMoneyAttempt.Terms(
            movement.kind(),
            movement.sourceId(),
            destination == null ? null : destination.getId(),
            movement.recipientId(),
            movement.commitmentId(),
            movement.selection(),
            movement.amount(),
            movement.provision(),
            resolved.configuredRate(),
            fee.amount(),
            fee.funding(),
            binding,
            JobOrderLinkedPaymentTerms.reported(bank));
    return submitPrepared(order, operationId, actor, terms, bank, counterparty);
  }

  JobOrderMoneyOutcome submitPrepared(
      JobOrder order,
      UUID operationId,
      UUID actor,
      JobOrderLinkedMoneyAttempt.Terms terms,
      JobOrderBankSelection bank,
      @Nullable Counterparty counterparty) {
    var attempt =
        attempts.saveAndFlush(
            new JobOrderLinkedMoneyAttempt(
                order.getId(), operationId, actor, terms, bank, counterparty, null));
    port()
        .submit(
            new JobOrderBankPort.Payment(
                attempt.getId(),
                attempt.getPurposeRef(),
                bank,
                terms.amount(),
                terms.rate(),
                terms.actualPayment(),
                counterparty));
    if (attempt.getState() == JobOrderLinkedMoneyState.SUBMITTING) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    return queries.projectLinkedOutcome(attempt);
  }

  private static @Nullable Counterparty movementParty(
      JobOrderBankPort port,
      JobOrderMoneyKind kind,
      JobOrderBankSelection bank,
      JobOrderMoneyHolder source,
      @Nullable JobOrderMoneyHolder destination,
      JobOrderMoneyRecipients.Party recipient) {
    if (bank.counterpartyUserId() != null || bank.counterpartyName() != null) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    return switch (bank.type()) {
      case DEPOSIT -> {
        if (kind != JobOrderMoneyKind.COLLECTION || destination == null) {
          throw JobOrderLinkedPaymentTerms.invalid();
        }
        requireCustodian(port, bank, destination);
        yield party(source);
      }
      case WITHDRAWAL -> {
        requireCustodian(port, bank, source);
        UUID recipientUser = recipient.accountId();
        yield new Counterparty(recipientUser, recipientUser == null ? recipient.name() : null);
      }
      case TRANSFER, HOLDER_TRANSFER -> {
        requireCustodian(port, bank, source);
        if (destination != null) {
          UUID user = destination.getUserId();
          if (user == null) {
            throw JobOrderLinkedPaymentTerms.invalid();
          }
          port.requireCustodian(Objects.requireNonNull(bank.targetCustodianId()), user);
        } else if (bank.type() == JobOrderBankSelection.Type.HOLDER_TRANSFER
            || recipient.usable()) {
          throw JobOrderLinkedPaymentTerms.invalid();
        }
        yield null;
      }
      case AUTHORISATION -> throw JobOrderLinkedPaymentTerms.invalid();
    };
  }

  JobOrderBankPort port() {
    JobOrderBankPort port = bankPort.getIfUnique();
    if (port == null) {
      throw new BadRequestException("orders.money.bankHandling.unavailable");
    }
    return port;
  }

  private static void requireCustodian(
      JobOrderBankPort port, JobOrderBankSelection bank, JobOrderMoneyHolder holder) {
    UUID user = holder.getUserId();
    if (user == null) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    port.requireCustodian(Objects.requireNonNull(bank.custodianId()), user);
  }

  static Counterparty party(JobOrderMoneyHolder holder) {
    return new Counterparty(holder.getUserId(), holder.getAccountlessName());
  }
}
