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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Recorded result of one accepted money operation.
 *
 * @param operationId submitted logical identity
 * @param holderId credited provision holder or debited movement holder, when person-held
 * @param amount exact net whole aUEC
 * @param bankHandling the submitted choice
 * @param recordedAt the booking time
 * @param holderName the permitted current holder label
 * @param actorName the permitted current original actor label
 * @param kind the recorded purpose
 * @param destinationHolderId original receiving collection holder, if any
 * @param recipientId original requester designation, if any
 * @param recipientName the permitted destination label
 * @param fee exact fee paid by the order
 * @param rate the retained fee rate for a movement
 * @param provision new money supplied in this operation
 * @param peerOrderId paired order when the caller may read it
 * @param corrections connected corrections accepted in this operation
 * @param correctionBasis current editable basis for this original in the order history
 * @param state the authoritative operation outcome
 * @param linkedRevision the submitted revision for a linked operation
 * @param actualPayment whether an actual player payment was recorded for a linked attempt
 * @param actualFee the linked game's fee, including any part not funded by the order
 * @param feeFunding the linked fee's source
 * @param counterpartyName the reported actual counterparty when distinct from the order holder
 * @param activeLinkedRevision whether this revision is the current linked attempt
 * @param clarificationResolved whether a targeted correction resolved a real-payment reversal
 * @param accountHoldingId the credited or debited order-local Bank account holding
 * @param patchLabel the current label of the patch change whose currency wipe booked the shortage
 *     or cancelled the attempt, or {@code null}
 */
public record JobOrderMoneyOutcome(
    UUID operationId,
    @Nullable UUID holderId,
    String amount,
    boolean bankHandling,
    Instant recordedAt,
    @Nullable String holderName,
    @Nullable String actorName,
    JobOrderMoneyKind kind,
    @Nullable UUID destinationHolderId,
    @Nullable UUID recipientId,
    @Nullable String recipientName,
    String fee,
    @Nullable String rate,
    String provision,
    @Nullable UUID peerOrderId,
    List<JobOrderMoneyCorrectionView> corrections,
    @Nullable JobOrderMoneyCorrectionBasisView correctionBasis,
    JobOrderLinkedMoneyState state,
    @Nullable Long linkedRevision,
    @Nullable Boolean actualPayment,
    @Nullable String actualFee,
    @Nullable JobOrderMoneyFeeFunding feeFunding,
    @Nullable String counterpartyName,
    @Nullable Boolean activeLinkedRevision,
    @Nullable Boolean clarificationResolved,
    @Nullable UUID accountHoldingId,
    @Nullable String patchLabel) {

  static JobOrderMoneyOutcome of(
      JobOrderMoneyOperation operation,
      @Nullable String holderName,
      @Nullable String actorName,
      @Nullable String recipientName,
      @Nullable UUID peerOrderId,
      List<JobOrderMoneyCorrectionView> corrections,
      @Nullable JobOrderMoneyCorrectionBasisView correctionBasis,
      @Nullable String patchLabel) {
    return new JobOrderMoneyOutcome(
        operation.getOperationId(),
        operation.getHolderId(),
        operation.getAmount().toPlainString(),
        operation.isBankHandling(),
        operation.getRecordedAt(),
        holderName,
        actorName,
        operation.getKind(),
        operation.getDestinationHolderId(),
        operation.getRecipientId(),
        recipientName,
        operation.feeAmount().toPlainString(),
        operation.getFeeRate() == null ? null : operation.getFeeRate().toPlainString(),
        operation.providedAmount().toPlainString(),
        peerOrderId,
        corrections,
        correctionBasis,
        JobOrderLinkedMoneyState.POSTED,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        operation.getAccountHoldingId(),
        patchLabel);
  }
}
