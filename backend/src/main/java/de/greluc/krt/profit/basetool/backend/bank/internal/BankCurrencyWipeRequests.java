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

import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAttempt;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels the pending Bank requests a currency wipe ends, each with the patch change as its reason
 * (REQ-PATCH-042, REQ-PATCH-043). A cancellation clears the staff's open request notices and tells
 * a purpose owner like any other cancellation; the Bank's trail records it in the wipe's summary
 * event, not one by one.
 */
@Component
@RequiredArgsConstructor
class BankCurrencyWipeRequests {

  private final BankBookingRequestRepository requests;
  private final BankPurposeObservers purposeObservers;
  private final ApplicationEventPublisher eventPublisher;
  private final BankLinkedRequestLiveSync liveSync;

  /**
   * Counts the requests {@link #cancelUnlinkedBefore} would cancel now.
   *
   * @param liveAt the live time of the patch
   * @return the pending requests submitted before it that no order attempt owns
   */
  long countUnlinkedBefore(@NotNull Instant liveAt) {
    return requests.countUnlinkedCreatedBefore(BankBookingRequestStatus.PENDING, liveAt);
  }

  /**
   * Cancels every pending request submitted before the live time that no order attempt owns.
   *
   * @param liveAt the live time of the patch
   * @param patchChangeId the patch change, kept as each request's reason
   * @return the number of requests cancelled
   */
  @Transactional(propagation = Propagation.MANDATORY)
  int cancelUnlinkedBefore(@NotNull Instant liveAt, @NotNull UUID patchChangeId) {
    List<BankBookingRequest> pending =
        requests.lockUnlinkedCreatedBefore(BankBookingRequestStatus.PENDING, liveAt);
    for (BankBookingRequest request : pending) {
      cancel(request, patchChangeId);
      purposeObservers.notify(
          request, (observer, ref) -> observer.onCancelled(request.getId(), ref));
    }
    return pending.size();
  }

  /**
   * Cancels the exact pending request of an order payment attempt its owner already cancelled.
   *
   * @param requestId the pending request
   * @param attempt the attempt the request must belong to
   * @param patchChangeId the patch change, kept as the request's reason
   * @throws NotFoundException when the request does not exist or belongs to another attempt
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void cancelLinked(
      @NotNull UUID requestId, @NotNull BankLinkedAttempt attempt, @NotNull UUID patchChangeId) {
    BankBookingRequest request =
        Entities.require(requests.findByIdForUpdate(requestId), "Booking request not found");
    BankLinkedAttemptRecord linked = request.getLinkedAttempt();
    if (linked == null || !linked.identifies(attempt)) {
      throw new NotFoundException("Booking request not found");
    }
    cancel(request, patchChangeId);
  }

  /**
   * Cancels one locked request and announces it.
   *
   * @param request the locked request
   * @param patchChangeId the patch change
   */
  private void cancel(@NotNull BankBookingRequest request, @NotNull UUID patchChangeId) {
    request.cancelByPatchChange(patchChangeId, Instant.now());
    eventPublisher.publishEvent(
        new BankBookingRequestCancelledEvent(request.getId(), request.accountId(), null));
    liveSync.changed();
  }
}
