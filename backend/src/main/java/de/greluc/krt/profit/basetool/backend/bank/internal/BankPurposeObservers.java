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

import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.BookingRequestPurposeObserver;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Lets the observer that owns a request's purpose kind lock the purpose before the bank locks the
 * request, and tells it how the request ended, inside the bank's transaction (REQ-BANK-063).
 */
@Component
@RequiredArgsConstructor
class BankPurposeObservers {

  /** Orders purposes by kind, then by reference as PostgreSQL orders UUIDs. */
  private static final Comparator<BankPurpose> PURPOSE_ORDER =
      Comparator.comparing(BankPurpose::kind)
          .thenComparing(BankPurpose::ref, BankPostingWriter.ACCOUNT_ID_ORDER);

  /** Every observer bean; each owns one purpose kind. */
  private final ObjectProvider<BookingRequestPurposeObserver> observers;

  /**
   * Lets the owner of each purpose lock it before the bank locks a request of it, one purpose at a
   * time in kind and reference order; a purpose whose kind has no observer is skipped.
   *
   * @param purposes the purposes of the requests the bank is about to lock
   */
  void beforeDecisions(@NotNull Collection<BankPurpose> purposes) {
    purposes.stream()
        .distinct()
        .sorted(PURPOSE_ORDER)
        .forEach(purpose ->
            observers
                .orderedStream()
                .filter(observer -> observer.kind() == purpose.kind())
                .forEach(observer -> observer.beforeDecision(purpose.ref())));
  }

  /**
   * Calls the observers of the request's purpose kind; nothing for a request without a purpose.
   *
   * @param request the request that just reached its terminal state
   * @param call the observer call, given the observer and the stored purpose reference
   */
  void notify(
      @NotNull BankBookingRequest request,
      @NotNull BiConsumer<BookingRequestPurposeObserver, UUID> call) {
    BankPurposeKind kind = request.getPurposeKind();
    UUID ref = request.getPurposeRef();
    if (kind == null || ref == null) {
      return;
    }
    observers
        .orderedStream()
        .filter(observer -> observer.kind() == kind)
        .forEach(observer -> call.accept(observer, ref));
  }
}
