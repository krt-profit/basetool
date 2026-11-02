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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.domain.Specification;

/**
 * How far the credited donations and deliveries cover an open need, as the needs list filters it
 * (REQ-SUP-006).
 */
public enum NeedCoverage {

  /** Nothing is credited to the need yet. */
  UNCOVERED,

  /** Some, but not all, of the amount is credited. */
  PARTIAL;

  /**
   * The filter that keeps the open needs of this coverage.
   *
   * @return the specification over {@link SupplyNeed}
   */
  public @NotNull Specification<SupplyNeed> specification() {
    return (root, query, cb) -> {
      var open = cb.equal(root.get("status"), NeedStatus.OPEN);
      var fulfilled = root.<Double>get("fulfilledAmount");
      return switch (this) {
        case UNCOVERED -> cb.and(open, cb.lessThanOrEqualTo(fulfilled, 0.0));
        case PARTIAL ->
            cb.and(
                open,
                cb.greaterThan(fulfilled, 0.0),
                cb.lessThan(fulfilled, root.<Double>get("amount")));
      };
    };
  }
}
