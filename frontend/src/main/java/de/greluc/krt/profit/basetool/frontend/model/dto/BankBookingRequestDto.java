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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend's bank booking-request payload (epic #666 F2, REQ-BANK-022/-023).
 * Serves both the requester's "my requests" list and the bank-staff confirmation queue; type/status
 * arrive as enum names rendered through i18n keys.
 *
 * @param id the request id
 * @param accountId the target org-unit account id
 * @param accountNo the target account's human-readable number
 * @param accountName the target account's display name
 * @param orgUnitId the owning org unit's id
 * @param orgUnitName the owning org unit's long-form name
 * @param orgUnitShorthand the owning org unit's shorthand, or {@code null}
 * @param type movement enum name ({@code DEPOSIT} / {@code WITHDRAWAL} / {@code TRANSFER})
 * @param amount the requested whole-aUEC amount
 * @param note the requester's optional note, or {@code null}
 * @param justification the requester's optional justification (Begr&uuml;ndung) for a {@code
 *     WITHDRAWAL} / {@code TRANSFER} (REQ-BANK-045), or {@code null}
 * @param status lifecycle enum name (PENDING / CONFIRMED / REJECTED / CANCELLED)
 * @param requesterHandle the requesting officer/lead's effective-name snapshot
 * @param holderId the holder recorded at confirmation, or {@code null} while not confirmed
 * @param holderHandle the recorded holder's display name (live effective name, snapshot fallback
 *     when the user is gone, REQ-BANK-003), or {@code null} while not confirmed
 * @param resultingTransactionId the booked ledger transaction id, or {@code null} while not
 *     confirmed
 * @param deciderHandle the deciding bank employee's handle, or {@code null} while pending/cancelled
 * @param rejectReason the rejection reason, or {@code null} unless rejected
 * @param decidedAt when the request reached its terminal state, or {@code null} while pending
 * @param createdAt when the request was raised
 * @param targetAccountId the destination account id for a {@code TRANSFER}, or {@code null}
 * @param targetAccountNo the destination account's number for a {@code TRANSFER}, or {@code null}
 * @param requiresOwnerApproval whether the amount exceeds the requester's approval limit
 *     (REQ-BANK-041)
 * @param applicableLimit the requester's resolved approval limit, or {@code null} = unlimited
 * @param requiredApprover which approver class a flagged request needs (REQ-BANK-041/-046) — {@code
 *     RESPONSIBLE_HOLDER} / {@code AREA_LEAD_PROFIT} / {@code ORGANISATIONSLEITUNG}, or {@code
 *     null} when no approval is needed
 * @param ownerApprovalGranted whether the responsible holder has granted in-app approval
 * @param ownerApprovalGrantedByHandle the responsible holder's handle who granted approval, or
 *     {@code null}
 * @param splitEnabled whether a deposit distributes a percentage across the squadron accounts on
 *     confirmation (REQ-BANK-044)
 * @param splitPercent the whole-percent (1–100) distributed across squadron accounts, or {@code
 *     null} when not a split
 * @param version the optimistic-locking version echoed on cancel/confirm/reject
 */
public record BankBookingRequestDto(
    UUID id,
    UUID accountId,
    String accountNo,
    String accountName,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName,
    @Nullable String orgUnitShorthand,
    @BackendEnumAsString String type,
    BigDecimal amount,
    @Nullable String note,
    @Nullable String justification,
    @BackendEnumAsString String status,
    String requesterHandle,
    @Nullable UUID holderId,
    @Nullable String holderHandle,
    @Nullable UUID resultingTransactionId,
    @Nullable String deciderHandle,
    @Nullable String rejectReason,
    @Nullable Instant decidedAt,
    Instant createdAt,
    @Nullable UUID targetAccountId,
    @Nullable String targetAccountNo,
    boolean requiresOwnerApproval,
    @Nullable BigDecimal applicableLimit,
    @Nullable String requiredApprover,
    boolean ownerApprovalGranted,
    @Nullable String ownerApprovalGrantedByHandle,
    boolean splitEnabled,
    @Nullable BigDecimal splitPercent,
    Long version) {}
