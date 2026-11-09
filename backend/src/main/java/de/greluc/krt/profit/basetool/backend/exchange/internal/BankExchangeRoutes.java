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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The bank route family of the backend's exchange surface (REQ-BXCH-006): every path at or below
 * {@value #PATH}, which the bank switch gates. A bank route only has to live there to be covered.
 */
public final class BankExchangeRoutes {

  /** The root of the bank route family below the relay prefix. */
  public static final String PATH = "/api/v1/exchange/bank";

  /** Not instantiable. */
  private BankExchangeRoutes() {}

  /**
   * Tells whether a path belongs to the bank route family.
   *
   * @param path a request URI, or {@code null}
   * @return {@code true} for {@value #PATH} and every path below it
   */
  @Contract(value = "null -> false", pure = true)
  public static boolean isBank(@Nullable String path) {
    return path != null && (path.equals(PATH) || path.startsWith(PATH + "/"));
  }

  /**
   * Tells whether the request bound to the current thread belongs to the bank route family.
   *
   * @return {@code true} when a servlet request is bound and its URI is a bank path
   */
  public static boolean isCurrentRequestBank() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    return attributes instanceof ServletRequestAttributes servlet
        && isBank(servlet.getRequest().getRequestURI());
  }
}
