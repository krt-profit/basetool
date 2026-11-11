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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Validated monetary terms shared by preview, booking and recovery.
 *
 * @param kind the purpose recorded with the booking
 * @param sourceId the original source holder
 * @param selection normalized collection counterparty or handover source
 * @param recipientId the original designation for a requester handover
 * @param amount net aUEC received
 * @param provision newly provided aUEC expressly supplied by the caller
 * @param commitmentId the unit commitment explicitly selected for this fresh provision
 */
record JobOrderMoneyMovementTerms(
    JobOrderMoneyKind kind,
    UUID sourceId,
    JobOrderMoneySelection selection,
    @Nullable UUID recipientId,
    BigDecimal amount,
    BigDecimal provision,
    @Nullable UUID commitmentId) {

  static JobOrderMoneyMovementTerms from(MoveJobOrderMoneyDto dto) {
    if (Boolean.TRUE.equals(dto.bankHandling())) {
      if (dto.bank() == null) {
        throw new BadRequestException("orders.money.bankHandling.unavailable");
      }
    } else if (dto.bank() != null) {
      throw JobOrderLinkedPaymentTerms.invalid();
    }
    JobOrderMoneySelection destination =
        JobOrderMoneySelection.parse(
            dto.destinationHolderId(), dto.destinationUserId(), dto.destinationName());
    UUID sourceId = dto.sourceHolderId();
    if (sourceId == null || dto.sourceAccountHoldingId() != null) {
      throw invalidSelection();
    }
    UUID recipientId = dto.recipientId();
    JobOrderMoneySelection selected =
        switch (dto.kind()) {
          case PROVISION,
              REQUESTER_RETURN,
              RELEASE,
              SHORTAGE,
              SETTLEMENT,
              PURPOSE_OUTFLOW,
              PURPOSE_INFLOW,
              CORRECTION ->
              throw invalidSelection();
          case COLLECTION -> {
            if (recipientId != null
                || dto.targetVersion() != null
                || destination.kind() == JobOrderMoneySelection.Kind.SELF) {
              throw invalidSelection();
            }
            yield destination;
          }
          case HANDOVER, PURPOSE_HANDOVER -> {
            if (recipientId == null
                || dto.destinationVersion() != null
                || destination.kind() != JobOrderMoneySelection.Kind.SELF) {
              throw invalidSelection();
            }
            yield new JobOrderMoneySelection(JobOrderMoneySelection.Kind.EXISTING, sourceId, null);
          }
        };
    BigDecimal provision =
        dto.provision() == null ? BigDecimal.ZERO : WholeAuec.nonnegative(dto.provision());
    if (dto.kind() == JobOrderMoneyKind.PURPOSE_HANDOVER && provision.signum() != 0) {
      throw new BadRequestException("orders.money.movement.purposeProvision");
    }
    if (dto.commitmentId() != null && provision.signum() == 0) {
      throw new BadRequestException("orders.money.commitment.provisionRequired");
    }
    return new JobOrderMoneyMovementTerms(
        dto.kind(),
        sourceId,
        selected,
        recipientId,
        WholeAuec.positive(dto.amount()),
        provision,
        dto.commitmentId());
  }

  static RateSnapshot submittedRate(@Nullable String rate) {
    if (rate == null || !rate.matches("[0-9]+(?:[.][0-9]+)?")) {
      throw new BadRequestException("orders.money.movement.rateRequired");
    }
    try {
      return new RateSnapshot(new BigDecimal(rate));
    } catch (IllegalArgumentException invalidRate) {
      throw new BadRequestException("orders.money.movement.rateRequired");
    }
  }

  boolean matches(JobOrderMoneyOperation operation, RateSnapshot rate) {
    return operation.getKind() == kind
        && sourceId.equals(operation.getHolderId())
        && Objects.equals(operation.getRecipientId(), recipientId)
        && Objects.equals(operation.getCommitmentId(), commitmentId)
        && operation.matchesSelection(selection)
        && amount.compareTo(operation.getAmount()) == 0
        && provision.compareTo(operation.providedAmount()) == 0
        && operation.getFeeRate() != null
        && rate.rate().compareTo(operation.getFeeRate()) == 0;
  }

  private static BadRequestException invalidSelection() {
    return new BadRequestException("orders.money.movement.selection.invalid");
  }
}
