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

package de.greluc.krt.profit.basetool.backend.bank.api;

import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** The required synchronous owner of the consequences of a linked Bank action. */
@ObserverSpi
public interface BankLinkedEffectHandler {

  /**
   * Identifies the handler's purpose.
   *
   * @return the handled purpose kind
   */
  @NotNull
  BankPurposeKind kind();

  /**
   * Acquires the owner's resources and validates the attempt before Bank takes decision locks.
   *
   * @param attempt the stable attempt and opaque purpose
   * @param action the action about to be taken
   * @param actorId the authenticated actor taking the action
   */
  void beforeEffect(
      @NotNull BankLinkedAttempt attempt, @NotNull Action action, @NotNull UUID actorId);

  /**
   * Applies the owner's consequences in the Bank transaction using the prepared resources.
   *
   * @param attempt the stable attempt and opaque purpose
   * @param outcome the Bank outcome
   * @param actorId the authenticated actor who took the action
   */
  void onOutcome(
      @NotNull BankLinkedAttempt attempt,
      @NotNull BankLinkedOutcome outcome,
      @NotNull UUID actorId);

  /** The Bank action whose owning resources must be prepared. */
  enum Action {
    /** Initial direct or requested submission. */
    SUBMIT,
    /** Staff confirmation. */
    CONFIRM,
    /** Staff rejection. */
    REJECT,
    /** Pending request cancellation. */
    CANCEL,
    /** Reversal of the original ledger transaction. */
    REVERSE
  }
}
