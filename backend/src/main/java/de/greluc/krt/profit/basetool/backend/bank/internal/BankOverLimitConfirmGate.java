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

import de.greluc.krt.profit.basetool.backend.bank.api.BankApproverNoteRequiredException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The gate a bank employee's confirm of an over-limit booking request passes (REQ-BANK-041,
 * ADR-0353): the confirm is an attestation, and without an in-app approval the staff note names the
 * approver.
 */
final class BankOverLimitConfirmGate {

  /** Not instantiable. */
  private BankOverLimitConfirmGate() {}

  /**
   * Passes a confirm unless the request needs approval and the attestation is missing, or the
   * approval was not recorded in the app and the note is blank after trimming.
   *
   * @param request the request being confirmed
   * @param ownerApprovalConfirmed whether the employee ticked "approval obtained"
   * @param staffNote the confirming employee's note; {@code null} when none
   * @throws BankConflictException with {@code BANK_OWNER_APPROVAL_REQUIRED} when the attestation is
   *     missing
   * @throws BankApproverNoteRequiredException when the attested confirm names no approver
   */
  static void require(
      @NotNull BankBookingRequest request,
      boolean ownerApprovalConfirmed,
      @Nullable String staffNote) {
    if (!request.isRequiresOwnerApproval()) {
      return;
    }
    if (!ownerApprovalConfirmed) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_OWNER_APPROVAL_REQUIRED,
          "The request exceeds the requester's approval limit; confirm the responsible holder's"
              + " approval first");
    }
    if (!request.isOwnerApprovalGranted() && (staffNote == null || staffNote.isBlank())) {
      throw new BankApproverNoteRequiredException();
    }
  }
}
