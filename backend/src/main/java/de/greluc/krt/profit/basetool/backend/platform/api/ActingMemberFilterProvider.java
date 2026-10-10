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

package de.greluc.krt.profit.basetool.backend.platform.api;

import jakarta.servlet.Filter;
import org.jetbrains.annotations.NotNull;

/**
 * Supplies the security-chain filter that authenticates an ingest gateway's request as the member
 * it acts for (ADR-0129, REQ-XCH-009), implemented by the exchange module (plan §5.3).
 *
 * <p>The security configuration places the filter directly after the bearer-token filter and the
 * approval filter directly after it, keyed on the returned filter's runtime class.
 */
public interface ActingMemberFilterProvider {

  /**
   * Creates the acting-member filter for one security filter chain.
   *
   * @return a new filter instance, never registered as a servlet filter on its own
   */
  @NotNull
  Filter actingMemberFilter();
}
