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
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
   * Counts every request the patch change cancelled so far, the unlinked ones and those of the order
   * attempts alike (REQ-PATCH-046).
   *
   * @param patchChangeId the patch change kept as their reason
   * @return the number of requests that name it
   */
  long countCancelledBy(@NotNull UUID patchChangeId) {
    return requests.countByCancelledByPatchChange(patchChangeId);
  }

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
   * Cancels every pending request submitted before the live time that no order attempt owns. The
   * owners of their purposes are locked first and the requests second, as an owner's own
   * cancellation locks them (REQ-BANK-063).
   *
   * @param liveAt the live time of the patch
   * @param patchChangeId the patch change, kept as each request's reason
   * @return the number of requests cancelled
   * @throws BusinessConflictException when a purpose-bound request turned up after its owners were
   *     locked
   */
  @Transactional(propagation = Propagation.MANDATORY)
  int cancelUnlinkedBefore(@NotNull Instant liveAt, @NotNull UUID patchChangeId) {
    Set<BankPurpose> owners =
        Set.copyOf(
            requests.findUnlinkedPurposesCreatedBefore(BankBookingRequestStatus.PENDING, liveAt));
    purposeObservers.beforeDecisions(owners);
    List<BankBookingRequest> pending =
        requests.lockUnlinkedCreatedBefore(BankBookingRequestStatus.PENDING, liveAt);
    for (BankBookingRequest request : pending) {
      requireOwnerLocked(request, owners);
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
   * Refuses a purpose-bound request whose owner was not locked before the request locks: one that
   * turned up between the owner read and the request lock would otherwise take its owner's lock
   * after its own.
   *
   * @param request the locked request
   * @param owners the purposes whose owners are locked
   * @throws BusinessConflictException when the request's owner is not among them
   */
  private static void requireOwnerLocked(
      @NotNull BankBookingRequest request, @NotNull Set<BankPurpose> owners) {
    BankPurposeKind kind = request.getPurposeKind();
    UUID ref = request.getPurposeRef();
    if (kind != null && ref != null && !owners.contains(new BankPurpose(kind, ref))) {
      throw new BusinessConflictException(
          "A Bank request arrived while the currency wipe ran; trigger the patch change again");
    }
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
