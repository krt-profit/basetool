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

package de.greluc.krt.profit.basetool.frontend.bank.model;

import java.util.UUID;

/**
 * Write payload for a bank employee confirming a pending booking request (REQ-BANK-023).
 *
 * @param holderId the holder recorded for the booking
 * @param destinationHolderId the destination holder of a transfer, or {@code null}
 * @param ownerApprovalConfirmed the attestation that the responsible holder approved; {@code null}
 *     counts as {@code false}
 * @param staffNote internal note of the confirming employee, or {@code null}
 * @param version the request version the client read
 */
public record ConfirmBankBookingRequest(
    UUID holderId,
    UUID destinationHolderId,
    Boolean ownerApprovalConfirmed,
    String staffNote,
    Long version) {}
