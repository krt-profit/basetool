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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import org.jetbrains.annotations.NotNull;

/**
 * The order section of the caller: one order and one version per navigation mode.
 *
 * @param member the member mode's order
 * @param admin the administration mode's order
 */
@DtoMirror
public record OrderResponse(@NotNull OrderModeResponse member, @NotNull OrderModeResponse admin) {

  /** The order of a caller who moved nothing in either mode. */
  public static final OrderResponse EMPTY =
      new OrderResponse(OrderModeResponse.EMPTY, OrderModeResponse.EMPTY);

  /**
   * Fills a mode the backend did not send with its empty default.
   *
   * @param member the member mode's order
   * @param admin the administration mode's order
   */
  public OrderResponse {
    member = member == null ? OrderModeResponse.EMPTY : member;
    admin = admin == null ? OrderModeResponse.EMPTY : admin;
  }
}
