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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One of the member's own booking requests, in the shape of the published {@code
 * bank-request.schema.json} (REQ-BXCH-003): no staff note, staff identities, counterparty, purpose
 * or confirm capability.
 *
 * @param id the request's id, opaque to the client
 * @param version the optimistic-lock version a correction or cancel echoes
 * @param status {@code PENDING}, {@code CONFIRMED}, {@code REJECTED} or {@code CANCELLED}
 * @param createdAt when it was raised
 * @param decidedAt when it reached its terminal state, or {@code null} while pending
 * @param type {@code DEPOSIT}, {@code WITHDRAWAL} or {@code TRANSFER}
 * @param amount the amount in whole aUEC
 * @param note the member's note, or {@code null}
 * @param justification the member's justification, or {@code null}
 * @param account the (source) account
 * @param targetAccount the destination of a transfer, or {@code null}
 * @param requiresOwnerApproval whether the amount exceeded the member's limit
 * @param requiredApprover {@code RESPONSIBLE_HOLDER}, {@code BANK_MANAGEMENT} or {@code
 *     ORGANISATIONSLEITUNG}, or {@code null} without an approval need
 * @param ownerApprovalGranted whether the approval was granted in the Basetool
 * @param rejectReason the staff's reason for a rejection, or {@code null}
 * @param client the connected application that raised it, or {@code null} for the Basetool
 * @param splitEnabled whether a deposit distributes {@code splitPercent} across squadron accounts
 * @param splitPercent the whole percent distributed, or {@code null} without a split
 */
public record ExchangeBankRequestDto(
    @NotNull UUID id,
    long version,
    @NotNull String status,
    @NotNull Instant createdAt,
    @Nullable Instant decidedAt,
    @NotNull String type,
    long amount,
    @Nullable String note,
    @Nullable String justification,
    @NotNull ExchangeBankRequestAccountDto account,
    @Nullable ExchangeBankRequestTargetDto targetAccount,
    boolean requiresOwnerApproval,
    @Nullable String requiredApprover,
    boolean ownerApprovalGranted,
    @Nullable String rejectReason,
    @Nullable String client,
    boolean splitEnabled,
    @Nullable Integer splitPercent) {}
