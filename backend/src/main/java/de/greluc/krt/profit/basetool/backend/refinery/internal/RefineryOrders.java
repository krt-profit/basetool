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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Lookups and audit-subject text shared by the refinery order services.
 *
 * <p>Stateless; every method takes what it needs.
 */
final class RefineryOrders {

  /** Units a good's output quantity counts per SCU of an SCU-measured output material. */
  static final double UNITS_PER_SCU = 100.0d;

  private RefineryOrders() {}

  /**
   * Loads an order or fails.
   *
   * @param repository the order repository
   * @param id refinery order primary key
   * @return the order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
   */
  static RefineryOrder require(@NotNull RefineryOrderRepository repository, @NotNull UUID id) {
    return Entities.require(repository.findById(id), "error.refinery_order.not_found");
  }

  /**
   * Builds the non-personal audit subject label {@code <method> · <location>} for a refinery order
   * (REQ-AUDIT-001).
   *
   * @param order the refinery order
   * @return the {@code <method> · <location>} label
   */
  @NotNull
  static String label(@NotNull RefineryOrder order) {
    return methodName(order) + " · " + locationName(order);
  }

  /**
   * The order's location name for an audit payload, or {@code -} when unset.
   *
   * @param order the refinery order
   * @return the location name or {@code -}
   */
  @NotNull
  static String locationName(@NotNull RefineryOrder order) {
    return order.getLocation() != null ? order.getLocation().getName() : "-";
  }

  /**
   * The order's refining-method name for an audit payload, or {@code none} when unset.
   *
   * @param order the refinery order
   * @return the method name or {@code none}
   */
  @NotNull
  static String methodName(@NotNull RefineryOrder order) {
    return order.getRefiningMethod() != null ? order.getRefiningMethod().getName() : "none";
  }
}
