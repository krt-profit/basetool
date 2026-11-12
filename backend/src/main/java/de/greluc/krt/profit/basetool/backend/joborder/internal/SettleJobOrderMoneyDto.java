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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * An actual settlement and the observed monetary revisions.
 *
 * @param operationId caller-generated identity, required to book
 * @param kind requester return, shortage, release or settlement
 * @param sourceHolderId the existing holder who pays the fee or funds the movement
 * @param sourceAccountHoldingId the source order account position for a release or shortage
 * @param sourceVersion observed source position revision
 * @param recipientId original requester designation returning its payment
 * @param destinationHolderId existing receiving order holder
 * @param destinationAccountHoldingId the receiving order account position for a requester return
 * @param destinationVersion observed receiver revision or zero for a new holder
 * @param destinationUserId selected receiving account
 * @param destinationName reported accountless beneficiary or receiving holder
 * @param targetVersion observed fulfilment target revision for a requester return
 * @param amount exact net principal
 * @param rate previewed applicable fee rate
 * @param provision explicit additional funds
 * @param bankHandling per-operation Bank choice
 * @param bank explicit Bank coordinates when coupling is selected
 */
public record SettleJobOrderMoneyDto(
    @Nullable UUID operationId,
    @NotNull JobOrderMoneyKind kind,
    @Nullable UUID sourceHolderId,
    @Nullable UUID sourceAccountHoldingId,
    @Nullable Long sourceVersion,
    @Nullable UUID recipientId,
    @Nullable UUID destinationHolderId,
    @Nullable UUID destinationAccountHoldingId,
    @Nullable Long destinationVersion,
    @Nullable UUID destinationUserId,
    @Nullable @Size(max = 200) String destinationName,
    @Nullable Long targetVersion,
    @NotBlank String amount,
    @Nullable String rate,
    @Nullable String provision,
    @NotNull Boolean bankHandling,
    @Nullable @Valid JobOrderBankSelection bank) {}
