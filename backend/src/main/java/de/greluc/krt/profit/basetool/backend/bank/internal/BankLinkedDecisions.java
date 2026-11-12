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

import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAttempt;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedEffectHandler;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedEffectHandler.Action;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedOutcome;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankLinkedAttemptRepository.AttemptLink;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankLinkedAttemptRepository.RequestLink;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Resolves the required owner before a linked Bank decision takes row locks. */
@Component
@RequiredArgsConstructor
class BankLinkedDecisions {
  private final BankLinkedAttemptRepository attempts;
  private final ObjectProvider<BankLinkedEffectHandler> handlers;
  private final BankCaller caller;
  private final BankActors actors;
  private final BankAccessRules policy;

  /**
   * Prepares one effect through its unique purpose handler.
   *
   * @param attempt the opaque attempt
   * @param action the Bank action
   * @return the prepared decision
   */
  @NotNull
  Prepared prepare(@NotNull BankLinkedAttempt attempt, @NotNull Action action) {
    UUID actor = caller.userId();
    if (actor == null) {
      throw new AccessDeniedException("A linked Bank decision requires an authenticated member");
    }
    List<BankLinkedEffectHandler> matches =
        handlers.stream().filter(handler -> handler.kind() == attempt.purpose().kind()).toList();
    if (matches.size() != 1) {
      throw new IllegalStateException("A linked Bank effect requires exactly one purpose handler");
    }
    BankLinkedEffectHandler handler = matches.getFirst();
    handler.beforeEffect(attempt, action, actor);
    return new Prepared(attempt, handler, actor);
  }

  /**
   * Prepares a request decision using immutable scalar references before acquiring Bank locks.
   *
   * @param requestId the pending request
   * @param action the Bank decision
   * @param expected the exact attempt when called by the owning purpose, or {@code null}
   * @return the prepared decision, or {@code null} for a legacy request
   */
  @Nullable
  Prepared forRequest(
      @NotNull UUID requestId, @NotNull Action action, @Nullable BankLinkedAttempt expected) {
    RequestLink link = attempts.findRequestLink(requestId).orElse(null);
    if (expected != null && (link == null || !expected.equals(identity(link)))) {
      throw new NotFoundException("Booking request not found");
    }
    if (link == null) {
      return null;
    }
    if (action == Action.CANCEL
        && expected == null
        && !Objects.equals(caller.userId(), link.getRequesterId())) {
      throw new NotFoundException("Booking request not found");
    }
    if (action == Action.CONFIRM || action == Action.REJECT) {
      BankStaffRights rights = rights();
      UUID accountId = link.getAccountId();
      boolean allowed =
          action == Action.CONFIRM
              ? rights.canBook(link.getType(), accountId)
              : link.getType() == BankBookingRequestType.HOLDER_TRANSFER
                  ? rights.staff()
                  : accountId != null && rights.canSee(accountId);
      if (!allowed) {
        throw new AccessDeniedException("The caller lacks the Bank capability for this decision");
      }
    }
    return prepare(identity(link), action);
  }

  /**
   * Prepares reversal of a linked posting before the ledger acquires its resources.
   *
   * @param transactionId the original posting
   * @return the prepared decision, or {@code null} for an ordinary transaction
   */
  @Nullable
  Prepared forTransaction(@NotNull UUID transactionId) {
    BankLinkedAttempt attempt =
        attempts.findPostingLink(transactionId).map(BankLinkedDecisions::identity).orElse(null);
    if (attempt == null) {
      return null;
    }
    requireReversalAuthority();
    return prepare(attempt, Action.REVERSE);
  }

  /** Requires current Bank management authority to reverse a linked posting. */
  void requireReversalAuthority() {
    if (!rights().management()) {
      throw new AccessDeniedException("Reversing a linked Bank posting requires Bank management");
    }
  }

  /**
   * Reads current staff grants for a linked decision after a resource wait.
   *
   * @return current staff rights
   */
  @NotNull
  BankStaffRights rights() {
    UUID actor = caller.userId();
    return policy.staffRights(actor == null ? caller.actor() : actors.of(actor));
  }

  /**
   * Restores a stored linked identity from scalar lookup results.
   *
   * @param link the scalar attempt key
   * @return the linked identity
   */
  @NotNull
  private static BankLinkedAttempt identity(@NotNull AttemptLink link) {
    return new BankLinkedAttempt(
        link.getAttemptId(), new BankPurpose(BankPurposeKind.AUEC_ORDER, link.getPurposeRef()));
  }

  /**
   * A prepared owner and authenticated actor kept for synchronous outcome handling.
   *
   * @param attempt the linked identity
   * @param handler the unique owner
   * @param actor the authenticated decision maker
   */
  record Prepared(
      @NotNull BankLinkedAttempt attempt,
      @NotNull BankLinkedEffectHandler handler,
      @NotNull UUID actor) {

    /**
     * Applies the owner's consequences in the Bank transaction.
     *
     * @param outcome the effect just performed
     */
    void complete(@NotNull BankLinkedOutcome outcome) {
      handler.onOutcome(attempt, outcome, actor);
    }
  }
}
