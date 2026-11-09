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

import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Maps booking requests to their web wire shape and judges the confirm action for a caller. */
final class BankBookingRequestDtos {

  /** Not instantiable. */
  private BankBookingRequestDtos() {}

  /**
   * Whether the caller may confirm the request now: it is still {@code PENDING}, the caller holds
   * the capability {@link BankBookingRequestService#confirm} requires for it, and the four-eyes
   * rule does not bar them. The over-limit attestation stays a confirm-time input and is not part
   * of this answer (REQ-BANK-041).
   *
   * @param request the request to judge
   * @param rights the caller's staff rights
   * @return {@code true} iff the confirm action applies to this row for this caller
   */
  static boolean callerMayConfirm(
      @NotNull BankBookingRequest request, @NotNull BankStaffRights rights) {
    return request.getStatus() == BankBookingRequestStatus.PENDING
        && rights.canBook(request.getType(), request.getAccount().getId())
        && !rights.boundByFourEyes(request.getRequestedBy());
  }

  /**
   * Maps a request entity to its wire shape, resolving the owning org unit, recorded holder and
   * resulting transaction (all eagerly fetched on the list/queue reads, lazily resolvable within
   * the single-row transactional paths).
   *
   * @param request the request entity
   * @param clientName the connected application it was raised through, or {@code null}
   * @param callerMayConfirm whether the caller may confirm it, or {@code null} when not judged
   * @return the wire DTO
   */
  @NotNull
  static BankBookingRequestDto of(
      @NotNull BankBookingRequest request,
      @Nullable String clientName,
      @Nullable Boolean callerMayConfirm) {
    BankAccount account = request.getAccount();
    OrgUnit orgUnit = account.getOrgUnit();
    BankHolder holder = request.getHolder();
    BankTransaction transaction = request.getResultingTransaction();
    BankAccount target = request.getTargetAccount();
    return new BankBookingRequestDto(
        request.getId(),
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        orgUnit == null ? null : orgUnit.getId(),
        orgUnit == null ? null : orgUnit.getName(),
        orgUnit == null ? null : orgUnit.getShorthand(),
        request.getType(),
        request.getAmount(),
        request.getNote(),
        request.getJustification(),
        request.getStaffNote(),
        request.getStatus(),
        request.getRequesterHandle(),
        holder == null ? null : holder.getId(),
        holder == null ? null : holder.getDisplayName(),
        transaction == null ? null : transaction.getId(),
        request.getDeciderHandle(),
        request.getRejectReason(),
        request.getDecidedAt(),
        request.getCreatedAt(),
        target == null ? null : target.getId(),
        target == null ? null : target.getAccountNo(),
        request.isRequiresOwnerApproval(),
        request.getApplicableLimit(),
        request.getRequiredApprover() == null ? null : request.getRequiredApprover().name(),
        request.isOwnerApprovalGranted(),
        request.getOwnerApprovalGrantedByHandle(),
        request.isSplitEnabled(),
        request.getSplitPercent(),
        request.getCounterpartyUserId(),
        request.getCounterpartyHandle(),
        request.getCounterpartyOrgUnitId(),
        request.getCounterpartyOrgUnitName(),
        request.getPurposeKind(),
        request.getPurposeRef(),
        clientName,
        request.getVersion(),
        callerMayConfirm);
  }
}
