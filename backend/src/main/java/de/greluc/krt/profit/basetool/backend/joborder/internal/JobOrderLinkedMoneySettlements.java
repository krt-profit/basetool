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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Counterparty;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Maps guarded settlement terms to an admitted Bank attempt. */
@Component
@RequiredArgsConstructor
class JobOrderLinkedMoneySettlements {
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final JobOrderLinkedMoneyCommands linked;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneySettlementBasis basis;

  boolean hasAttempt(UUID orderId, UUID operationId) {
    return attempts.existsByOrderIdAndOperationIdAndActiveTrue(orderId, operationId);
  }

  Optional<JobOrderMoneyOutcome> replay(
      JobOrder order,
      UUID operationId,
      UUID actor,
      JobOrderMoneySettlementTerms terms,
      RateSnapshot rate,
      JobOrderBankSelection bank) {
    UUID orderId = order.getId();
    return attempts
        .findByOrderIdAndOperationIdAndActiveTrue(orderId, operationId)
        .map(attempt -> {
          if (!actor.equals(attempt.getActorId())) {
            throw new AccessDeniedException("Caller may not replay this operation.");
          }
          if (!terms.matches(attempt, rate) || !attempt.matchesBank(bank)) {
            throw new BusinessConflictException("orders.money.operation.termsMismatch");
          }
          UUID recipientId = attempt.getRecipientId();
          UUID payer =
              recipientId == null
                  ? ledger.read(orderId).holder(attempt.personalHolderId()).getUserId()
                  : recipients.accountFor(orderId, recipientId);
          basis.authorize(order, attempt.getKind(), actor, payer);
          return queries.projectLinkedOutcome(attempt);
        });
  }

  void validateParties(JobOrderMoneySettlementBasis.Resolved settled, JobOrderBankSelection bank) {
    bankCounterparty(settled, bank);
  }

  JobOrderMoneyOutcome submit(
      JobOrder order,
      UUID operationId,
      UUID actor,
      JobOrderMoneySettlementTerms terms,
      JobOrderMoneySettlementBasis.Resolved settled,
      JobOrderBankSelection bank) {
    Counterparty counterparty = bankCounterparty(settled, bank);
    var fee = Objects.requireNonNull(settled.linkedFee());
    var source = settled.source();
    var destination = settled.destination();
    if (destination != null && destination.getId() == null) {
      destination.recordChange();
      holders.saveAndFlush(destination);
    }
    var holding = source == null ? Objects.requireNonNull(destination) : source;
    BigDecimal chargedPrincipal = terms.requesterReturn() ? BigDecimal.ZERO : terms.amount();
    BigDecimal binding =
        chargedPrincipal.add(fee.orderFee()).subtract(terms.provision()).max(BigDecimal.ZERO);
    var effect =
        new JobOrderLinkedMoneyAttempt.Terms(
            terms.kind(),
            Objects.requireNonNull(holding.getId()),
            destination == null ? null : destination.getId(),
            terms.requesterRecipientId(),
            null,
            terms.recordedSelection(),
            terms.amount(),
            terms.provision(),
            settled.rate(),
            fee.amount(),
            fee.funding(),
            binding,
            JobOrderLinkedPaymentTerms.reported(bank));
    return linked.submitPrepared(order, operationId, actor, effect, bank, counterparty);
  }

  private @Nullable Counterparty bankCounterparty(
      JobOrderMoneySettlementBasis.Resolved settled, JobOrderBankSelection bank) {
    JobOrderBankPort port = linked.port();
    UUID sourceCustodian = Objects.requireNonNull(bank.custodianId());
    UUID payerBankAccount = settled.payerBankAccount();
    if (payerBankAccount != null) {
      if (!payerBankAccount.equals(bank.accountId())
          || (bank.type() != JobOrderBankSelection.Type.WITHDRAWAL
              && bank.type() != JobOrderBankSelection.Type.TRANSFER)) {
        throw JobOrderLinkedPaymentTerms.invalid();
      }
      if (settled.source() != null) {
        requireCustodian(port, sourceCustodian, settled.source().getUserId());
      }
    } else if (bank.type() != JobOrderBankSelection.Type.DEPOSIT) {
      requireCustodian(port, sourceCustodian, settled.payer().accountId());
    }
    return switch (bank.type()) {
      case WITHDRAWAL -> counterparty(settled.beneficiary());
      case TRANSFER, HOLDER_TRANSFER -> {
        requireCustodian(
            port,
            Objects.requireNonNull(bank.targetCustodianId()),
            settled.beneficiary().accountId());
        yield null;
      }
      case DEPOSIT -> {
        requireCustodian(port, sourceCustodian, settled.beneficiary().accountId());
        yield counterparty(settled.payer());
      }
      case AUTHORISATION -> throw JobOrderLinkedPaymentTerms.invalid();
    };
  }

  private static void requireCustodian(JobOrderBankPort port, UUID custodian, @Nullable UUID user) {
    if (user == null) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    port.requireCustodian(custodian, user);
  }

  private static Counterparty counterparty(JobOrderMoneyRecipients.Party party) {
    UUID user = party.accountId();
    if (user != null) {
      return new Counterparty(user, null);
    }
    if (!party.usable()) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    return new Counterparty(null, party.name());
  }
}
