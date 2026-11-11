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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
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
 * @param state the authoritative outcome
 * @param linkedRevision the submitted revision for a linked operation
 * @param actualPayment whether the linked attempt reports a player payment
 * @param actualFee the full linked game fee
 * @param feeFunding the source of the linked fee
 * @param counterpartyName the depositor when independently reported
 * @param activeLinkedRevision whether this revision is the current linked attempt
 * @param clarificationResolved whether a targeted correction resolved clarification
 * @param accountHoldingId the credited or debited order-local Bank account holding
 */
@DtoMirror
public record JobOrderMoneyOutcome(
    UUID operationId,
    @Nullable UUID holderId,
    String amount,
    boolean bankHandling,
    Instant recordedAt,
    @Nullable String holderName,
    @Nullable String actorName,
    @BackendEnumAsString String kind,
    @Nullable UUID destinationHolderId,
    @Nullable UUID recipientId,
    @Nullable String recipientName,
    String fee,
    @Nullable String rate,
    String provision,
    @Nullable UUID peerOrderId,
    List<JobOrderMoneyCorrectionView> corrections,
    @Nullable JobOrderMoneyCorrectionBasisView correctionBasis,
    @BackendEnumAsString String state,
    @Nullable Long linkedRevision,
    @Nullable Boolean actualPayment,
    @Nullable String actualFee,
    @Nullable @BackendEnumAsString String feeFunding,
    @Nullable String counterpartyName,
    @Nullable Boolean activeLinkedRevision,
    @Nullable Boolean clarificationResolved,
    @Nullable UUID accountHoldingId) {}
