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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Owns preparation and effects for the exact linked Bank attempt. */
public interface JobOrderBankEffects {

  /**
   * Stabilizes the owning resources before a Bank decision takes its request and account locks.
   *
   * @param attemptId the exact Bank attempt
   * @param purposeRef the opaque lineage
   * @param action the Bank action
   * @param actorId the authenticated Bank actor
   */
  void prepare(
      @NotNull UUID attemptId,
      @NotNull UUID purposeRef,
      @NotNull Action action,
      @NotNull UUID actorId);

  /**
   * Applies the outcome using the prepared order resources.
   *
   * @param attemptId the exact Bank attempt
   * @param purposeRef the opaque lineage
   * @param decision the Bank outcome
   * @param actorId the authenticated Bank actor
   */
  void apply(
      @NotNull UUID attemptId,
      @NotNull UUID purposeRef,
      @NotNull Decision decision,
      @NotNull UUID actorId);

  /** Bank actions whose order resources must be prepared. */
  enum Action {
    /** Direct or requested submission. */
    SUBMIT,
    /** Confirmation after Bank admission. */
    CONFIRM,
    /** Staff refusal of the request. */
    REJECT,
    /** Cancellation of a pending request. */
    CANCEL,
    /** Terminal cleanup of a prepared unexecuted request. */
    CANCEL_UNEXECUTED,
    /** Reversal of the linked ledger entry. */
    REVERSE
  }

  /**
   * The authoritative Bank outcome references.
   *
   * @param kind the outcome meaning
   * @param id the request, transaction, authorisation or reversal identity
   * @param originalId the linked original when reversed
   */
  record Decision(@NotNull Outcome kind, @NotNull UUID id, @Nullable UUID originalId) {}

  /** Meanings of a decided linked effect. */
  enum Outcome {
    /** A request awaits its decision. */
    WAITING,
    /** A ledger transaction posted. */
    POSTED,
    /** Existing funds were authorised. */
    AUTHORISED,
    /** Staff declined the request. */
    REJECTED,
    /** The pending request was withdrawn. */
    CANCELLED,
    /** The original ledger transaction was reversed. */
    REVERSED
  }
}
