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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The one place the exchange builds the bank actor of a caller: {@link BankActors#forExchange} for
 * the admitted member, so every bank read and write of the exchange decides for the member's roles
 * without Admin, their memberships and grants (REQ-BXCH-001).
 */
@Component
@RequiredArgsConstructor
class ExchangeBankActors {

  private final BankActors bankActors;

  /**
   * The bank actor of the member a connected application acts for.
   *
   * @param caller the admitted member and client
   * @return the member's actor without the Admin role
   */
  @NotNull
  BankActor of(@NotNull ExchangeCaller caller) {
    return bankActors.forExchange(caller.member());
  }
}
