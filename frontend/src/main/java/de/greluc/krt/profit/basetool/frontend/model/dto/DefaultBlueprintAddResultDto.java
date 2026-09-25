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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Outcome of one staged add on the admin default-blueprints page (REQ-INV-017), used to choose the
 * toast and keep only the failed keys staged for a retry.
 *
 * @param added how many keys the backend accepted as new defaults
 * @param skipped how many keys were already a default (the backend's {@code 409}), silently skipped
 * @param failedKeys the keys whose add failed for any other reason, in the order they were sent
 */
public record DefaultBlueprintAddResultDto(
    int added, int skipped, @NotNull @Unmodifiable List<String> failedKeys) {

  /**
   * Copies {@code failedKeys} into an unmodifiable list.
   *
   * @param added how many keys the backend accepted as new defaults
   * @param skipped how many keys were already a default and were skipped
   * @param failedKeys the keys whose add failed for any other reason
   */
  public DefaultBlueprintAddResultDto {
    failedKeys = List.copyOf(failedKeys);
  }
}
