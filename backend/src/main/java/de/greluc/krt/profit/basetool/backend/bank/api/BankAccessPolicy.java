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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The one bank access policy (ADR-0352): every bank access rule, decided for an explicit {@link
 * BankActor} and never from the security context. The web and the exchange ask the same queries;
 * the web passes the active-org-unit pin, the exchange none. Must run inside a transaction.
 */
public interface BankAccessPolicy {

  /**
   * What the actor may do with one account.
   *
   * @param actor the actor
   * @param accountId the account
   * @param pin the active org unit narrowing the org-unit view, or {@code null}
   * @return {@link BankAccountAccess.Visible} with the actor's rights, or {@link
   *     BankAccountAccess.Hidden} for an unknown account or one without any right
   */
  @NotNull
  BankAccountAccess accessTo(@NotNull BankActor actor, @NotNull UUID accountId, @Nullable UUID pin);

  /**
   * The active accounts the actor views on the org-unit side (REQ-BANK-021, REQ-BANK-064).
   *
   * @param actor the actor
   * @param pin the active org unit narrowing the view, or {@code null} for the whole reach
   * @return the accounts with {@link BankAccountRight#VIEW}, ordered by account number
   */
  @NotNull
  @Unmodifiable
  List<BankAccountAccess.Visible> viewableAccounts(@NotNull BankActor actor, @Nullable UUID pin);

  /**
   * Whether the actor may raise a booking request on the account and how it is routed
   * (REQ-BANK-039…042, REQ-BANK-047). A deposit is admitted on any existing account without
   * approval (REQ-BANK-042); a withdrawal or transfer needs {@link BankAccountRight#VIEW} and a
   * request-capable account.
   *
   * @param actor the requester
   * @param accountId the (source) account
   * @param pin the active org unit narrowing the view, or {@code null}
   * @param type the movement kind
   * @param amount the whole-aUEC amount
   * @return the routing, or why the request is refused
   */
  @NotNull
  BankAdmission admit(
      @NotNull BankActor actor,
      @NotNull UUID accountId,
      @Nullable UUID pin,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount);

  /**
   * What the actor may do as bank staff and approver, with no read beyond the actor itself.
   *
   * @param actor the actor
   * @return the staff rights
   */
  @NotNull
  BankStaffRights staffRights(@NotNull BankActor actor);
}
