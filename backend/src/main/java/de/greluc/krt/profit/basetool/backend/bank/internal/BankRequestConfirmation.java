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

import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestConfirmedEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Records the confirmation of a Bank booking request. */
@Component
@RequiredArgsConstructor
class BankRequestConfirmation {
  private final BankRequestCreation creation;
  private final BankAuditService bankAuditService;

  /**
   * Records the confirmed Bank request and builds its original decision notice.
   *
   * @param request the request being confirmed
   * @param holder the recorded custodian, absent for an account-only effect
   * @param transaction the resulting posting, or {@code null} for authorisation
   * @param staffNote the private staff note
   * @param decider the current Bank actor
   * @return the Bank decision event
   */
  @NotNull
  BankBookingRequestConfirmedEvent record(
      @NotNull BankBookingRequest request,
      @Nullable BankHolder holder,
      @Nullable BankTransaction transaction,
      @Nullable String staffNote,
      @Nullable UUID decider) {
    request.setHolder(holder);
    request.setResultingTransaction(transaction);
    request.setStatus(BankBookingRequestStatus.CONFIRMED);
    request.setStaffNote(staffNote);
    request.setDecidedBy(decider);
    request.setDeciderHandle(creation.resolveHandle(decider));
    request.setDecidedAt(Instant.now());

    UUID accountId = request.accountId();
    UUID transactionId = transaction == null ? null : transaction.getId();
    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_CONFIRMED,
        accountId,
        transactionId,
        request.getRequestedBy(),
        "confirmed "
            + request.getType()
            + " request "
            + request.shortId()
            + (request.getLinkedAttempt() == null
                ? " @" + Objects.requireNonNull(holder).getHandle()
                : ""));
    if (request.isRequiresOwnerApproval()) {
      bankAuditService.record(
          BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED,
          accountId,
          transactionId,
          request.getRequestedBy(),
          "owner approval confirmed for request " + request.shortId());
    }
    return new BankBookingRequestConfirmedEvent(
        request.getId(),
        accountId,
        request.accountNo(),
        request.getAmount(),
        request.getRequestedBy(),
        decider);
  }
}
