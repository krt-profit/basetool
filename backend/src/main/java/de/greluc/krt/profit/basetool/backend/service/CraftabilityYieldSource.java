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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Stock a member will own once a pending process finishes, as the blueprint craftability counts it
 * beside the Lager (plan §5.3); implemented by the refinery for its open orders.
 */
public interface CraftabilityYieldSource {

  /**
   * One pending yield total.
   *
   * @param materialId the output material
   * @param quality the output quality
   * @param totalScu the summed yield in SCU
   */
  record YieldSlice(UUID materialId, Integer quality, Double totalScu) {}

  /**
   * Sums the pending yield of the member into one SCU total per (output material, quality).
   *
   * @param userId the owning member
   * @return one slice per (output material, quality), never {@code null}
   */
  @NotNull
  List<YieldSlice> pendingYieldSlices(@NotNull UUID userId);
}
