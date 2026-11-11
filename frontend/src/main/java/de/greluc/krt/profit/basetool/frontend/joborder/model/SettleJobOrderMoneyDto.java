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

import com.fasterxml.jackson.annotation.JsonInclude;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * An exact settlement movement and the observed monetary revisions.
 *
 * @param operationId the logical identity, absent on previews
 * @param kind requester return, shortage, release or settlement
 * @param sourceHolderId the existing holder who pays the fee or funds the movement
 * @param sourceVersion the observed source revision
 * @param recipientId the original requester designation returning a payment
 * @param destinationHolderId the existing receiving holder
 * @param destinationVersion the receiving holder's revision or zero for a new holder
 * @param destinationUserId the selected receiving account
 * @param destinationName the reported accountless recipient name
 * @param targetVersion the observed fulfilment target revision for a requester return
 * @param amount the exact net amount
 * @param rate the previewed applicable fee rate
 * @param provision the fee money explicitly added by the source
 * @param bankHandling the per-operation choice of Bank handling
 * @param bank the selected Bank effect and its parties
 */
@DtoMirror
public record SettleJobOrderMoneyDto(
    @Nullable UUID operationId,
    @NotNull @BackendEnumAsString String kind,
    @Nullable UUID sourceHolderId,
    @Nullable Long sourceVersion,
    @Nullable UUID recipientId,
    @Nullable UUID destinationHolderId,
    @Nullable Long destinationVersion,
    @Nullable UUID destinationUserId,
    @Nullable @Size(max = 200) String destinationName,
    @Nullable Long targetVersion,
    @NotBlank String amount,
    @Nullable String rate,
    @Nullable String provision,
    @NotNull Boolean bankHandling,
    @Nullable @Valid @JsonInclude(JsonInclude.Include.NON_NULL) JobOrderBankSelection bank) {}
