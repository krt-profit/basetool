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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable purpose, participants and amounts of one settlement.
 *
 * @param kind recorded purpose
 * @param sourceId existing source or fee payer
 * @param requesterRecipientId original requester designation making an actual return
 * @param destination explicitly selected destination
 * @param amount exact net principal
 * @param provision explicit provision of missing fee funds
 */
record JobOrderMoneySettlementTerms(
    JobOrderMoneyKind kind,
    @Nullable UUID sourceId,
    @Nullable UUID requesterRecipientId,
    JobOrderMoneySelection destination,
    BigDecimal amount,
    BigDecimal provision) {

  static JobOrderMoneySettlementTerms from(SettleJobOrderMoneyDto dto) {
    validateBank(dto);
    var destination =
        JobOrderMoneySelection.parse(
            dto.destinationHolderId(), dto.destinationUserId(), dto.destinationName());
    BigDecimal provision =
        dto.provision() == null ? BigDecimal.ZERO : WholeAuec.nonnegative(dto.provision());
    boolean hasDestination = destination.kind() != JobOrderMoneySelection.Kind.SELF;
    if ((dto.kind() == JobOrderMoneyKind.REQUESTER_RETURN) != (dto.recipientId() != null)
        || (dto.kind() != JobOrderMoneyKind.REQUESTER_RETURN
            && (dto.targetVersion() != null || dto.destinationVersion() != null))
        || (dto.sourceHolderId() == null && dto.sourceVersion() != null)) {
      throw invalidSelection();
    }
    return switch (dto.kind()) {
      case REQUESTER_RETURN -> {
        if (!hasDestination) {
          throw invalidSelection();
        }
        yield terms(dto, destination, provision);
      }
      case SETTLEMENT -> {
        if (dto.sourceHolderId() == null
            || !hasDestination
            || destination.kind() == JobOrderMoneySelection.Kind.EXISTING) {
          throw invalidSelection();
        }
        yield terms(dto, destination, provision);
      }
      case RELEASE, SHORTAGE -> {
        if (dto.sourceHolderId() == null || hasDestination || provision.signum() != 0) {
          throw invalidSelection();
        }
        yield terms(dto, destination, provision);
      }
      case PROVISION,
          COLLECTION,
          HANDOVER,
          PURPOSE_HANDOVER,
          PURPOSE_INFLOW,
          PURPOSE_OUTFLOW,
          CORRECTION ->
          throw invalidSelection();
    };
  }

  private static void validateBank(SettleJobOrderMoneyDto dto) {
    JobOrderBankSelection bank = dto.bank();
    if (Boolean.TRUE.equals(dto.bankHandling()) != (bank != null)) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    if (bank == null) {
      return;
    }
    JobOrderLinkedPaymentTerms.validate(bank);
    if (bank.counterpartyName() != null || bank.counterpartyUserId() != null) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    boolean supported =
        switch (dto.kind()) {
          case SETTLEMENT ->
              JobOrderLinkedPaymentTerms.reported(bank)
                  && (bank.type() == JobOrderBankSelection.Type.WITHDRAWAL
                      || bank.type() == JobOrderBankSelection.Type.TRANSFER);
          case REQUESTER_RETURN -> bank.type() != JobOrderBankSelection.Type.AUTHORISATION;
          case PROVISION,
              COLLECTION,
              HANDOVER,
              PURPOSE_HANDOVER,
              RELEASE,
              SHORTAGE,
              PURPOSE_INFLOW,
              PURPOSE_OUTFLOW,
              CORRECTION ->
              false;
        };
    if (!supported) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
  }

  private static JobOrderMoneySettlementTerms terms(
      SettleJobOrderMoneyDto dto, JobOrderMoneySelection destination, BigDecimal provision) {
    return new JobOrderMoneySettlementTerms(
        dto.kind(),
        dto.sourceHolderId(),
        dto.recipientId(),
        destination,
        WholeAuec.positive(dto.amount()),
        provision);
  }

  boolean requesterReturn() {
    return kind == JobOrderMoneyKind.REQUESTER_RETURN;
  }

  boolean hasFee() {
    return requesterReturn() || kind == JobOrderMoneyKind.SETTLEMENT;
  }

  JobOrderMoneySelection recordedSelection() {
    return hasFee()
        ? destination
        : new JobOrderMoneySelection(JobOrderMoneySelection.Kind.EXISTING, sourceId, null);
  }

  boolean matches(JobOrderMoneyOperation fact, RateSnapshot rate) {
    if (kind != fact.getKind()
        || !Objects.equals(requesterRecipientId, fact.getRecipientId())
        || !fact.matchesSelection(recordedSelection())
        || amount.compareTo(fact.getAmount()) != 0
        || provision.compareTo(fact.providedAmount()) != 0) {
      return false;
    }
    UUID recordedSource =
        requesterReturn() && Objects.equals(fact.getHolderId(), fact.getDestinationHolderId())
            ? null
            : fact.getHolderId();
    return Objects.equals(sourceId, recordedSource)
        && (hasFee()
            ? rate.rate().compareTo(Objects.requireNonNull(fact.getFeeRate())) == 0
            : rate.rate().signum() == 0);
  }

  boolean matches(JobOrderLinkedMoneyAttempt attempt, RateSnapshot rate) {
    if (kind != attempt.getKind() || attempt.getAccountHoldingId() != null) {
      return false;
    }
    UUID holderId = attempt.personalHolderId();
    boolean sameHolding = requesterReturn() && holderId.equals(attempt.getDestinationHolderId());
    boolean sourceMatches =
        Objects.equals(sourceId, holderId)
            || (sourceId == null && sameHolding && attempt.getSourceBinding().signum() == 0);
    return sourceMatches
        && Objects.equals(requesterRecipientId, attempt.getRecipientId())
        && recordedSelection().equals(attempt.selection())
        && amount.compareTo(attempt.getAmount()) == 0
        && provision.compareTo(attempt.getProvision()) == 0
        && rate.equals(attempt.rateSnapshot());
  }

  static BadRequestException invalidSelection() {
    return new BadRequestException("orders.money.settlement.selection.invalid");
  }
}
