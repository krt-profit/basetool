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
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Raises, corrects and cancels a member's own booking requests for a connected application
 * (REQ-BXCH-004, REQ-BANK-067), with the web's validation and the bank access policy's routing for
 * an explicit actor and no pin. A raised request records the calling client.
 *
 * <p>An unknown, closed or hidden account and another member's request are all {@code
 * NotFoundException}; the bank's own conflicts stay {@link BankConflictException}, among them
 * {@code BANK_OPEN_REQUEST_CAP} once the member holds the configured number of pending requests.
 */
public interface BankOwnRequestWrites {

  /**
   * Raises a booking request for the actor.
   *
   * @param actor the requester, from {@link BankActors#forExchange}
   * @param request what to raise
   * @param clientId the registry id of the connected application the gate admitted, recorded on the
   *     request
   * @return the pending request
   */
  @NotNull
  Written raise(@NotNull BankActor actor, @NotNull NewRequest request, @NotNull String clientId);

  /**
   * Corrects one of the actor's pending, not yet approved requests (REQ-BANK-056); the counterparty
   * the request names stays as it is.
   *
   * @param actor the requester
   * @param requestId the request
   * @param correction the corrected values and the echoed version
   * @return the corrected request
   */
  @NotNull
  Written correct(
      @NotNull BankActor actor, @NotNull UUID requestId, @NotNull Correction correction);

  /**
   * Cancels one of the actor's pending requests.
   *
   * @param actor the requester
   * @param requestId the request
   * @param version the echoed version
   * @return the cancelled request
   */
  @NotNull
  Written cancel(@NotNull BankActor actor, @NotNull UUID requestId, long version);

  /**
   * A request to raise; a deposit may target any active account (REQ-BANK-042).
   *
   * @param type deposit, withdrawal or transfer
   * @param sourceAccountId the (source) account
   * @param targetAccountId the destination of a transfer, else {@code null}
   * @param amount the whole-aUEC amount
   * @param note the requester's note, or {@code null}
   * @param justification the requester's justification, or {@code null}
   * @param splitEnabled whether a deposit distributes {@code splitPercent} across squadron accounts
   * @param splitPercent the whole percent to distribute, or {@code null} without a split
   */
  record NewRequest(
      @NotNull BankBookingRequestType type,
      @NotNull UUID sourceAccountId,
      @Nullable UUID targetAccountId,
      @NotNull BigDecimal amount,
      @Nullable String note,
      @Nullable String justification,
      boolean splitEnabled,
      @Nullable BigDecimal splitPercent) {}

  /**
   * The corrected values of a request, each replacing the stored one.
   *
   * @param amount the corrected whole-aUEC amount
   * @param note the corrected note, or {@code null} to clear it
   * @param justification the corrected justification, or {@code null} to clear it
   * @param targetAccountId the corrected destination of a transfer, else {@code null}
   * @param version the echoed version
   */
  record Correction(
      @NotNull BigDecimal amount,
      @Nullable String note,
      @Nullable String justification,
      @Nullable UUID targetAccountId,
      long version) {}

  /**
   * A request after a write.
   *
   * @param id the request
   * @param status the lifecycle state's name, such as {@code PENDING}
   * @param version its optimistic-lock version after the write
   * @param amount the whole-aUEC amount
   */
  record Written(
      @NotNull UUID id, @NotNull String status, long version, @NotNull BigDecimal amount) {}
}
