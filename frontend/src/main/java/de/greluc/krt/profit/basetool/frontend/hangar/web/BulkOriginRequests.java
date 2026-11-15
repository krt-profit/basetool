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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipBulkOriginRequestDto;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipOrigin;
import org.jetbrains.annotations.Nullable;

/**
 * The checks a bulk origin request passes in the frontend before it is forwarded, shared by „Meine
 * Schiffe" and the admin member hangar (REQ-HANGAR-018).
 */
final class BulkOriginRequests {

  private BulkOriginRequests() {}

  /**
   * Returns the request to forward, or {@code null} when it selects no ship or names no origin the
   * page offers.
   *
   * @param request the posted request, or {@code null}
   * @return the request with its origin normalized, or {@code null} to refuse it
   */
  static @Nullable ShipBulkOriginRequestDto validated(@Nullable ShipBulkOriginRequestDto request) {
    if (request == null || request.shipIds() == null || request.shipIds().isEmpty()) {
      return null;
    }
    ShipOrigin origin = ShipOrigin.choiceOf(request.origin());
    return origin == null ? null : new ShipBulkOriginRequestDto(request.shipIds(), origin.name());
  }
}
