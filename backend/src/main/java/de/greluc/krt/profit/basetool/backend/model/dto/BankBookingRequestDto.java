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

package de.greluc.krt.profit.basetool.backend.model.dto;

import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Wire shape of a {@link de.greluc.krt.profit.basetool.backend.model.BankBookingRequest} (epic #666
 * F2). Serves both the requester's own "my requests" list (REQ-BANK-022) and the bank-staff
 * confirmation queue (REQ-BANK-023) — the surfacing endpoints, not this DTO, enforce that a
 * requester only ever sees their own rows and a staffer only the accounts they may see.
 *
 * @param id the request id
 * @param accountId the target org-unit account id
 * @param accountNo the target account's human-readable number
 * @param accountName the target account's display name
 * @param orgUnitId the owning org unit's id
 * @param orgUnitName the owning org unit's long-form name
 * @param orgUnitShorthand the owning org unit's shorthand, or {@code null}
 * @param type deposit or withdrawal
 * @param amount the requested whole-aUEC amount
 * @param note the requester's optional note, or {@code null}
 * @param justification the requester's optional justification (Begr&uuml;ndung) for a {@code
 *     WITHDRAWAL} / {@code TRANSFER} (REQ-BANK-045), or {@code null}
 * @param staffNote the confirming bank employee's own note ("Notiz Bankmitarbeiter", REQ-BANK-054),
 *     snapshotted on the request at confirmation; {@code null} while the request is pending, on a
 *     rejected/cancelled one and when the employee recorded none
 * @param status the lifecycle state (PENDING / CONFIRMED / REJECTED / CANCELLED)
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
 * @param requiresOwnerApproval whether the requested amount exceeded the requester's approval
 *     limit, so confirmation needs the responsible-holder approval attestation (REQ-BANK-041)
 * @param applicableLimit the requester's resolved approval limit at creation, or {@code null} =
 *     unlimited
 * @param requiredApprover which approver class a flagged request needs (REQ-BANK-041/-046) — {@code
 *     RESPONSIBLE_HOLDER} / {@code BANK_MANAGEMENT} / {@code ORGANISATIONSLEITUNG} as an enum name,
 *     or {@code null} when the request needs no approval
 * @param ownerApprovalGranted whether the responsible holder has granted in-app approval (pre-fills
 *     the bank employee's confirmation checkbox)
 * @param ownerApprovalGrantedByHandle the responsible holder's handle who granted approval, or
 *     {@code null}
 * @param splitEnabled whether a deposit distributes a percentage across the squadron accounts on
 *     confirmation (REQ-BANK-044)
 * @param splitPercent the whole-percent (1–100) distributed across squadron accounts, or {@code
 *     null} when not a split
 * @param counterpartyUserId the Empf&auml;nger the requester named on a {@code WITHDRAWAL}
 *     (REQ-BANK-055), or {@code null} — then confirmation derives the requester (REQ-BANK-044).
 *     Carried so the edit modal can pre-select the picker
 * @param counterpartyHandle deletion-proof name snapshot of {@code counterpartyUserId}, the value
 *     the request lists actually display; {@code null} exactly when no Empf&auml;nger is named
 * @param counterpartyOrgUnitId the named Empf&auml;nger's chosen org unit, or {@code null}
 * @param counterpartyOrgUnitName name snapshot of that org unit, or {@code null}
 * @param version the optimistic-locking version the client echoes on cancel/confirm/reject
 */
public record BankBookingRequestDto(
    UUID id,
    UUID accountId,
    String accountNo,
    String accountName,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName,
    @Nullable String orgUnitShorthand,
    BankBookingRequestType type,
    BigDecimal amount,
    @Nullable String note,
    @Nullable String justification,
    @Nullable String staffNote,
    BankBookingRequestStatus status,
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
    @Nullable UUID counterpartyUserId,
    @Nullable String counterpartyHandle,
    @Nullable UUID counterpartyOrgUnitId,
    @Nullable String counterpartyOrgUnitName,
    Long version) {

  /**
   * This request with {@link #staffNote} blanked — the requester-facing projection (REQ-BANK-054).
   *
   * <p>The "Notiz Bankmitarbeiter" is a bank-<em>internal</em> remark about processing the request
   * and is redacted from the member audience exactly like the Halter columns of the org-unit
   * booking history. The requester's own "Meine Anträge" list is that audience; the responsible
   * holder's "Fremde Anträge" list is <strong>not</strong> and keeps the note, because approving is
   * the decision the note exists to inform. A responsible holder who raised the request themselves
   * therefore sees it only under the approver lens — deliberate, not a rendering bug.
   *
   * @return {@code this} when no staff note is set, else a copy with the note removed
   */
  public BankBookingRequestDto withoutStaffNote() {
    if (staffNote == null) {
      return this;
    }
    return new BankBookingRequestDto(
        id,
        accountId,
        accountNo,
        accountName,
        orgUnitId,
        orgUnitName,
        orgUnitShorthand,
        type,
        amount,
        note,
        justification,
        null,
        status,
        requesterHandle,
        holderId,
        holderHandle,
        resultingTransactionId,
        deciderHandle,
        rejectReason,
        decidedAt,
        createdAt,
        targetAccountId,
        targetAccountNo,
        requiresOwnerApproval,
        applicableLimit,
        requiredApprover,
        ownerApprovalGranted,
        ownerApprovalGrantedByHandle,
        splitEnabled,
        splitPercent,
        counterpartyUserId,
        counterpartyHandle,
        counterpartyOrgUnitId,
        counterpartyOrgUnitName,
        version);
  }
}
