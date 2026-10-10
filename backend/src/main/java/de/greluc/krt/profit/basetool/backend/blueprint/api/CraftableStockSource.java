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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The stock a member owns, as the blueprint craftability counts it (plan §5.3); implemented by the
 * inventory module over the Lager.
 */
public interface CraftableStockSource {

  /**
   * Sums the member's own Lager stock, personal and shared, into one SCU total per material and
   * quality.
   *
   * @param userId the owning member
   * @return one slice per (material, quality), never {@code null}
   */
  @NotNull
  List<CraftabilitySlice> ownedStockSlices(@NotNull UUID userId);
}
