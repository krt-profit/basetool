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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for a bank employee confirming a pending booking request (REQ-BANK-023).
 *
 * @param holderId the holder recorded for the booking; the source holder for a transfer
 * @param destinationHolderId the destination holder for a transfer; {@code null} otherwise
 * @param ownerApprovalConfirmed the attestation that the responsible holder approved; required for
 *     a request flagged {@code requiresOwnerApproval} (REQ-BANK-041), ignored otherwise
 * @param staffNote the confirming employee's optional internal note (REQ-BANK-054), copied onto the
 *     booked transaction
 * @param version the request's echoed {@code @Version}; a mismatch surfaces as 409
 */
public record ConfirmBankBookingRequest(
    @NotNull UUID holderId,
    @Nullable UUID destinationHolderId,
    boolean ownerApprovalConfirmed,
    @Nullable @Size(max = 500) String staffNote,
    @NotNull Long version) {}
