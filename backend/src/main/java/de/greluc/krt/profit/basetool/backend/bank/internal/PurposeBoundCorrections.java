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

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * What a requester's correction may change on a purpose-bound request (REQ-BANK-056, REQ-BANK-061):
 * the purpose module checked the amount, the destination and the recipient when it raised the
 * request and books against them, so they stay; note and Begr&uuml;ndung stay correctable.
 */
final class PurposeBoundCorrections {

  private PurposeBoundCorrections() {}

  /**
   * Refuses a correction of a purpose-bound request that changes its amount, destination or
   * recipient; a request without a purpose passes.
   *
   * @param request the locked request
   * @param update the correction
   * @throws BadRequestException when a purpose-bound request's amount, destination or recipient
   *     would change
   */
  static void requireTermsKept(
      @NotNull BankBookingRequest request, @NotNull UpdateBankBookingRequest update) {
    if (request.getPurposeKind() == null) {
      return;
    }
    BankAccount target = request.getTargetAccount();
    boolean kept =
        request.getAmount().compareTo(update.amount()) == 0
            && Objects.equals(target == null ? null : target.getId(), update.targetAccountId())
            && Objects.equals(request.getCounterpartyUserId(), update.counterpartyUserId())
            && Objects.equals(request.getCounterpartyOrgUnitId(), update.counterpartyOrgUnitId());
    if (!kept) {
      throw new BadRequestException(
          "A purpose-bound request keeps its amount, destination and recipient;"
              + " cancel it and raise it anew in its module");
    }
  }
}
