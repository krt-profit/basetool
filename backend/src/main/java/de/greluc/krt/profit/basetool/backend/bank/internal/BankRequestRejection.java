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

import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestRejectedEvent;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Records the rejection of a Bank booking request (REQ-BANK-023, REQ-BANK-041). */
@Component
@RequiredArgsConstructor
class BankRequestRejection {
  private final BankRequestCreation creation;
  private final BankAuditService bankAuditService;

  /**
   * Flips the request to {@code REJECTED} with the reason and the decider, audits it and builds its
   * decision notice.
   *
   * @param request the locked, pending and authorized request
   * @param reason the rejection reason shown to the requester
   * @param decider the rejecting member, or {@code null}
   * @return the Bank decision event
   */
  @NotNull
  BankBookingRequestRejectedEvent record(
      @NotNull BankBookingRequest request, @NotNull String reason, @Nullable UUID decider) {
    request.setStatus(BankBookingRequestStatus.REJECTED);
    request.setRejectReason(reason);
    request.setDecidedBy(decider);
    request.setDeciderHandle(creation.resolveHandle(decider));
    request.setDecidedAt(Instant.now());

    UUID accountId = request.accountId();
    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_REJECTED,
        accountId,
        null,
        request.getRequestedBy(),
        "rejected request " + request.shortId());
    return new BankBookingRequestRejectedEvent(
        request.getId(),
        accountId,
        request.accountNo(),
        request.getAmount(),
        reason,
        request.getRequestedBy(),
        decider);
  }
}
