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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wire shape of a {@link BankBookingRequest}, used by the requester's own list (REQ-BANK-022) and
 * the bank-staff confirmation queue (REQ-BANK-023). Visibility is enforced by the endpoints, not by
 * this DTO.
 *
 * @param id the request id
 * @param accountId the account id, absent for a holder transfer
 * @param accountNo the human-readable account number, absent for a holder transfer
 * @param accountName the account's display name, absent for a holder transfer
 * @param orgUnitId the owning org unit's id
 * @param orgUnitName the owning org unit's long-form name
 * @param orgUnitShorthand the owning org unit's shorthand, or {@code null}
 * @param type the requested Bank action
 * @param amount the requested whole-aUEC amount
 * @param note the requester's optional note, or {@code null}
 * @param justification the requester's Begr&uuml;ndung for a {@code WITHDRAWAL} / {@code TRANSFER}
 *     (REQ-BANK-045), or {@code null}
 * @param staffNote the confirming employee's note (REQ-BANK-054), or {@code null} unless confirmed
 *     with one
 * @param status the lifecycle state (PENDING / CONFIRMED / REJECTED / CANCELLED)
 * @param requesterHandle the requester's effective-name snapshot
 * @param holderId the frozen or confirmed holder, or {@code null} for an unconfirmed ordinary
 *     request
 * @param holderHandle the frozen or confirmed holder's display name
 * @param resultingTransactionId the resulting ledger transaction id, or {@code null} without a
 *     booking
 * @param deciderHandle the deciding bank employee's handle, or {@code null} while pending/cancelled
 * @param rejectReason the rejection reason, or {@code null} unless rejected
 * @param decidedAt when the request reached its terminal state, or {@code null} while pending
 * @param createdAt when the request was raised
 * @param targetAccountId the destination account id for a {@code TRANSFER}, or {@code null}
 * @param targetAccountNo the destination account's number for a {@code TRANSFER}, or {@code null}
 * @param requiresOwnerApproval whether the amount exceeded the requester's approval limit
 *     (REQ-BANK-041)
 * @param applicableLimit the requester's approval limit at creation, or {@code null} = unlimited
 * @param requiredApprover the approver class a flagged request needs as an enum name, or {@code
 *     null} when no approval is needed
 * @param ownerApprovalGranted whether the responsible holder has granted in-app approval
 * @param ownerApprovalGrantedByHandle the approving responsible holder's handle, or {@code null}
 * @param splitEnabled whether a deposit is split across the squadron accounts (REQ-BANK-044)
 * @param splitPercent the whole percent (1–100) split across squadron accounts, or {@code null}
 * @param counterpartyUserId the registered depositor or payee, if supplied
 * @param counterpartyHandle the name of the actual depositor or payee, including an accountless
 *     player
 * @param counterpartyOrgUnitId the named Empf&auml;nger's chosen org unit, or {@code null}
 * @param counterpartyOrgUnitName name snapshot of that org unit, or {@code null}
 * @param purposeKind what another module raised the request for through the bank command API
 *     (REQ-BANK-061), or {@code null} for a request a member or employee filed
 * @param purposeRef that module's opaque reference, or {@code null} exactly when there is no
 *     purpose
 * @param clientName the connected application the request was raised through (REQ-BANK-067), or
 *     {@code null} for a request raised in the Basetool
 * @param version the optimistic-locking version echoed on cancel/confirm/reject
 * @param callerMayConfirm on the bank-staff queue, whether the caller may confirm this request now
 *     (pending, and the capability the confirm endpoint requires); {@code null} on every other read
 *     (REQ-BANK-023)
 * @param linked frozen and final evidence of an order-linked request, or {@code null}
 * @param cancelledByPatchLabel the current label of the patch change whose currency wipe cancelled
 *     the request, or {@code null} for every other request (REQ-PATCH-042)
 */
public record BankBookingRequestDto(
    UUID id,
    @Nullable UUID accountId,
    @Nullable String accountNo,
    @Nullable String accountName,
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
    @Nullable BankPurposeKind purposeKind,
    @Nullable UUID purposeRef,
    @Nullable String clientName,
    Long version,
    @Nullable Boolean callerMayConfirm,
    @Nullable LinkedBankRequestDetails linked,
    @Nullable String cancelledByPatchLabel) {

  /**
   * Returns this request with {@link #staffNote} blanked, the requester-facing projection
   * (REQ-BANK-054).
   *
   * @return {@code this} when no staff note is set, else a copy with the note removed
   */
  @NotNull
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
        purposeKind,
        purposeRef,
        clientName,
        version,
        callerMayConfirm,
        linked,
        cancelledByPatchLabel);
  }

  /**
   * Evidence for an order-linked request.
   *
   * @param destinationHolderId the frozen destination holder for a transfer
   * @param destinationHolderHandle the destination holder's display name
   * @param fee the whole-aUEC fee under the submitted terms
   * @param authorisationId the resulting authority reference on an approved no-ledger request
   */
  public record LinkedBankRequestDetails(
      @Nullable UUID destinationHolderId,
      @Nullable String destinationHolderHandle,
      BigDecimal fee,
      @Nullable UUID authorisationId) {}
}
