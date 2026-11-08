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

import de.greluc.krt.profit.basetool.backend.identity.api.events.UserRolesChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Brings a member's bank holder in line with a committed role change at once, instead of at the
 * next roster sync (REQ-BANK-029).
 *
 * <p>The reconcile runs in a transaction of its own after the role change committed; a failure is
 * logged and leaves the change itself standing, and the daily sweep repairs the registry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BankHolderRoleChangeListener {

  private final BankHolderReconciliationService reconciliationService;

  /**
   * Reconciles the holder of the member whose role set changed.
   *
   * @param event the committed role change
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRolesChanged(@NotNull UserRolesChangedEvent event) {
    try {
      reconciliationService.reconcileUser(event.userId());
    } catch (DataAccessException | TransactionException e) {
      log.error(
          "Bank holder reconcile for user {} failed; the next roster sync retries.",
          event.userId(),
          e);
    }
  }
}
