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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The order and comment versions a MATERIAL or ITEM edit observed, carried as one value so the two
 * are never split or swapped (REQ-ORDERS-046, REQ-ORDERS-066). A payload unwraps it into its flat
 * {@code version} and {@code commentVersion} fields; a create sends both empty.
 *
 * @param version the observed order version
 * @param commentVersion the observed comment version
 */
public record JobOrderObservedVersions(@Nullable Long version, @Nullable Long commentVersion) {

  /**
   * The versions an edit of the given order starts from.
   *
   * @param order the order as the edit page read it
   * @return its order version and its comment version
   */
  @Contract("_ -> new")
  public static @NotNull JobOrderObservedVersions of(@NotNull JobOrderDto order) {
    return new JobOrderObservedVersions(order.version(), order.commentVersion());
  }
}
