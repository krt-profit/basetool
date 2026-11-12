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
 * An exact order money movement and its observed basis.
 *
 * @param operationId the logical submission identity, absent for a preview
 * @param kind the purpose of the movement
 * @param sourceHolderId the debited holder when person-held
 * @param sourceVersion the observed source revision
 * @param targetVersion the observed target revision for a handover
 * @param destinationHolderId an existing collection destination
 * @param destinationUserId the explicitly selected account for collection
 * @param destinationName the entered accountless collection destination
 * @param destinationVersion the observed collection destination revision
 * @param recipientId the designated handover recipient
 * @param amount the exact net principal
 * @param provision the explicitly supplied additional funds
 * @param commitmentId the explicitly selected unit commitment occurrence for new provision
 * @param rate the server-previewed fee rate, absent for a preview
 * @param bankHandling whether Bank handling was selected
 * @param bank the selected Bank effect and its parties
 * @param sourceAccountHoldingId the debited order-local Bank account holding
 */
@DtoMirror
public record MoveJobOrderMoneyDto(
    @Nullable UUID operationId,
    @NotNull @BackendEnumAsString String kind,
    @Nullable UUID sourceHolderId,
    @NotNull Long sourceVersion,
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
    @Nullable @Valid @JsonInclude(JsonInclude.Include.NON_NULL) JobOrderBankSelection bank,
    @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) UUID sourceAccountHoldingId) {}
