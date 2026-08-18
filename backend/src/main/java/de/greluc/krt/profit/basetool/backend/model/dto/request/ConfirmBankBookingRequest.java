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
 * Write payload for a bank employee confirming a pending booking request (REQ-BANK-023/-040/-041):
 * the holder(s) the employee records, the over-limit owner-approval attestation and the echoed
 * optimistic-locking version to detect a concurrent decision.
 *
 * <p>For a {@code TRANSFER} request {@link #holderId} is the source holder and {@link
 * #destinationHolderId} the destination holder (both recorded on the booked transfer); for {@code
 * DEPOSIT} / {@code WITHDRAWAL} only {@link #holderId} is used. {@link #ownerApprovalConfirmed}
 * must be {@code true} to confirm a request flagged {@code requiresOwnerApproval} (REQ-BANK-041) —
 * the "approval by the responsible holder obtained" checkbox — and is ignored otherwise.
 *
 * @param holderId the holder recorded for the booked transaction (source holder for a transfer)
 * @param destinationHolderId the destination holder for a transfer; {@code null} otherwise
 * @param ownerApprovalConfirmed whether the employee attests the responsible holder's approval was
 *     obtained (required when the request exceeds the requester's limit)
 * @param staffNote the confirming employee's own free-text note ("Notiz Bankmitarbeiter",
 *     REQ-BANK-054) recording internal context for the booking; always optional, snapshotted on the
 *     request and copied onto the transaction the confirmation books
 * @param version the request's echoed {@code @Version}; a mismatch surfaces as 409
 */
public record ConfirmBankBookingRequest(
    @NotNull UUID holderId,
    @Nullable UUID destinationHolderId,
    boolean ownerApprovalConfirmed,
    @Nullable @Size(max = 500) String staffNote,
    @NotNull Long version) {}
