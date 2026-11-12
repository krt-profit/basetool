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
 * Terms of a movement and the exact revisions on which it relies.
 *
 * @param operationId caller-generated identity, required to book
 * @param kind collection, handover or same-person purpose handover
 * @param sourceHolderId the debited personal holder
 * @param sourceAccountHoldingId the debited order-local Bank account position
 * @param sourceVersion the observed source holder revision
 * @param targetVersion the observed target revision for a requester handover
 * @param destinationHolderId the existing collection holder, if selected
 * @param destinationUserId the selected receiving account, if applicable
 * @param destinationName the accountless collection holder's name, if applicable
 * @param destinationVersion the observed collection holder revision, or zero for a new holder
 * @param recipientId the designated requester recipient for a handover
 * @param amount exact net whole aUEC
 * @param provision explicitly recorded new means, if needed
 * @param commitmentId the explicitly selected unit commitment occurrence for new provision
 * @param rate the rate returned by preview, required to book
 * @param bankHandling the explicit handling choice
 * @param bank selected Bank terms when coupling is requested
 */
public record MoveJobOrderMoneyDto(
    @Nullable UUID operationId,
    @NotNull JobOrderMoneyKind kind,
    @Nullable UUID sourceHolderId,
    @Nullable UUID sourceAccountHoldingId,
    @Nullable Long sourceVersion,
    @Nullable Long targetVersion,
    @Nullable UUID destinationHolderId,
    @Nullable UUID destinationUserId,
    @Nullable @Size(max = 200) String destinationName,
    @Nullable Long destinationVersion,
    @Nullable UUID recipientId,
    @NotBlank String amount,
    @Nullable String provision,
    @Nullable UUID commitmentId,
    @Nullable String rate,
    @NotNull Boolean bankHandling,
    @Valid @Nullable JobOrderBankSelection bank) {}
