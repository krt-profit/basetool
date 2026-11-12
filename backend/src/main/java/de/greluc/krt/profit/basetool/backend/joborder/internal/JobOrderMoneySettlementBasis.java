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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Resolves a settlement's actual parties, exact costs and current authority. */
@Component
@RequiredArgsConstructor
class JobOrderMoneySettlementBasis {

  private static final RateSnapshot NO_FEE = new RateSnapshot(BigDecimal.ZERO);

  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyHolders holders;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyLedger ledger;
  private final TransferFeePolicy feePolicy;
  private final JobOrderLinkedPaymentTerms paymentTerms;

  Resolved resolve(
      JobOrder order,
      SettleJobOrderMoneyDto dto,
      JobOrderMoneySettlementTerms terms,
      UUID actor,
      @Nullable RateSnapshot expectedRate) {
    UUID orderId = order.getId();
    JobOrderMoneyHolder source =
        terms.sourceId() == null
            ? null
            : holders.resolve(
                orderId,
                actor,
                new JobOrderMoneySelection(
                    JobOrderMoneySelection.Kind.EXISTING, terms.sourceId(), null));
    JobOrderMoneyRecipient designation =
        terms.requesterRecipientId() == null
            ? null
            : recipients.require(orderId, terms.requesterRecipientId());
    JobOrderMoneyRecipients.Party payer =
        designation == null ? party(source) : recipients.party(designation);
    authorize(order, terms.kind(), actor, payer.accountId());
    assertSettlementStatus(order);
    JobOrderMoneyHolder destination =
        terms.requesterReturn() ? holders.resolve(orderId, actor, terms.destination()) : null;
    var beneficiary = destination == null ? beneficiary(terms.destination()) : party(destination);
    UUID payerBankAccount = designation == null ? null : designation.getBankAccountId();
    var bank = dto.bank();
    if (designation != null && designation.isSuperseded()) {
      throw new UnavailableSettlement("orders.money.recipient.identity.changed");
    }
    boolean personReturn = terms.requesterReturn() && payerBankAccount == null;
    boolean gamePayment = bank == null || JobOrderLinkedPaymentTerms.reported(bank);
    if ((payerBankAccount != null && bank == null)
        || (personReturn && !payer.usable())
        || (terms.hasFee() && gamePayment && payer.sameKnownPerson(beneficiary))
        || (personReturn && source != null && !payer.sameKnownPerson(party(source)))) {
      throw JobOrderMoneySettlementTerms.invalidSelection();
    }
    if (source != null) {
      requireRevision(source, dto.sourceVersion());
    }
    if (destination != null) {
      requireRevision(destination, dto.destinationVersion());
    }
    var current = ledger.read(orderId);
    BigDecimal delivered = current.handedOver();
    BigDecimal overpayment = BigDecimal.ZERO;
    boolean reopens = false;
    if (designation != null) {
      var target = targets.findById(orderId).orElseThrow();
      OptimisticLock.check(
          target.getVersion(), dto.targetVersion(), JobOrderMoneyTarget.class, orderId);
      if (current
              .availableForPersonalReturn(Objects.requireNonNull(designation.getId()), null)
              .compareTo(terms.amount())
          < 0) {
        throw new UnavailableSettlement("orders.money.settlement.return.excess");
      }
      delivered = delivered.subtract(terms.amount());
      overpayment = delivered.subtract(target.getAmount()).max(BigDecimal.ZERO);
      reopens =
          order.getStatus() == JobOrderStatus.COMPLETED
              && delivered.compareTo(target.getAmount()) < 0;
    }
    RateSnapshot rate = terms.hasFee() ? feePolicy.snapshot() : NO_FEE;
    if (expectedRate != null && !expectedRate.equals(rate)) {
      throw new UnavailableSettlement("orders.money.movement.rateRequired");
    }
    var linkedFee =
        bank == null ? null : paymentTerms.feeFor(terms.kind(), bank, terms.amount(), rate);
    BigDecimal fee =
        linkedFee == null ? feePolicy.feeOn(terms.amount(), rate) : linkedFee.orderFee();
    if (terms.provision().compareTo(fee) > 0) {
      throw new BadRequestException("orders.money.settlement.feeProvision.excess");
    }
    BigDecimal held = current.availableBalance(source == null ? null : source.getId());
    BigDecimal chargedPrincipal = terms.requesterReturn() ? BigDecimal.ZERO : terms.amount();
    if (held.compareTo(chargedPrincipal) < 0) {
      throw new UnavailableSettlement("orders.money.settlement.insufficient");
    }
    BigDecimal sourceAfter = held.subtract(chargedPrincipal).subtract(fee).add(terms.provision());
    BigDecimal destinationAfter =
        destination == null
            ? null
            : current.availableBalance(destination.getId()).add(terms.amount());
    return new Resolved(
        source,
        destination,
        payer,
        beneficiary,
        payerBankAccount,
        rate,
        fee,
        linkedFee,
        sourceAfter,
        overpayment,
        destinationAfter,
        reopens);
  }

  private JobOrderMoneyRecipients.Party beneficiary(JobOrderMoneySelection selection) {
    if (selection.kind() == JobOrderMoneySelection.Kind.LINKED) {
      UUID id = Objects.requireNonNull(selection.reference());
      var selected =
          holders
              .activeUser(id)
              .orElseThrow(() -> new UnavailableSettlement("orders.money.holder.accountInactive"));
      return new JobOrderMoneyRecipients.Party(null, id, selected.getEffectiveName());
    }
    return new JobOrderMoneyRecipients.Party(null, null, selection.name());
  }

  static void assertSettlementStatus(JobOrder order) {
    if (order.getStatus() == JobOrderStatus.REJECTED) {
      throw new UnavailableSettlement("orders.money.movement.inactive");
    }
  }

  void authorize(JobOrder order, JobOrderMoneyKind kind, UUID actor, @Nullable UUID sourceAccount) {
    if (!access.responsible(order)
        && !actor.equals(sourceAccount)
        && !(kind == JobOrderMoneyKind.REQUESTER_RETURN && access.requester(order))) {
      throw new AccessDeniedException("Caller may not settle these order funds.");
    }
  }

  private JobOrderMoneyRecipients.Party party(@Nullable JobOrderMoneyHolder holder) {
    return holder == null
        ? new JobOrderMoneyRecipients.Party(null, null, null)
        : new JobOrderMoneyRecipients.Party(
            holder.getId(), holder.getUserId(), holders.nameOf(holder));
  }

  private static void requireRevision(JobOrderMoneyHolder holder, @Nullable Long version) {
    OptimisticLock.check(holder.getRevision(), version, JobOrderMoneyHolder.class, holder.getId());
  }

  /**
   * Verified settlement basis.
   *
   * @param source existing source or fee payer
   * @param destination receiving order holder for a return
   * @param payer the actual sending player
   * @param beneficiary the actual recipient
   * @param payerBankAccount the exact Bank account designated for the original handover
   * @param rate the current fee policy snapshot
   * @param fee order-funded fee
   * @param linkedFee actual game fee and funding for a linked settlement
   * @param sourceAfter remaining funds after the movement
   * @param overpayment amount remaining above target after a return
   * @param destinationAfter resulting receiving holding for a return
   * @param reopens whether an effective return takes the completed order below its target
   */
  record Resolved(
      @Nullable JobOrderMoneyHolder source,
      @Nullable JobOrderMoneyHolder destination,
      JobOrderMoneyRecipients.Party payer,
      JobOrderMoneyRecipients.Party beneficiary,
      @Nullable UUID payerBankAccount,
      RateSnapshot rate,
      BigDecimal fee,
      @Nullable JobOrderLinkedPaymentTerms.Fee linkedFee,
      BigDecimal sourceAfter,
      BigDecimal overpayment,
      @Nullable BigDecimal destinationAfter,
      boolean reopens) {

    JobOrderMoneyMovementPreview preview(
        JobOrderMoneySettlementTerms terms, @Nullable JobOrderMoneyBankPreview bank) {
      return new JobOrderMoneyMovementPreview(
          terms.amount().toPlainString(),
          fee.toPlainString(),
          rate.rate().toPlainString(),
          terms.provision().toPlainString(),
          sourceAfter.max(BigDecimal.ZERO).toPlainString(),
          payer.name(),
          beneficiary.name(),
          overpayment.toPlainString(),
          sourceAfter.negate().max(BigDecimal.ZERO).toPlainString(),
          destinationAfter == null ? null : destinationAfter.toPlainString(),
          false,
          reopens,
          bank);
    }
  }

  /** Describes a current settlement basis that cannot support the proposed movement. */
  static final class UnavailableSettlement extends RuntimeException {
    UnavailableSettlement(String messageKey) {
      super(messageKey);
    }

    BusinessConflictException previewProblem() {
      return new BusinessConflictException(Objects.requireNonNull(getMessage()));
    }
  }
}
