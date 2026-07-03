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

package de.greluc.krt.profit.basetool.backend.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Inbound request payload for the Add Unit operation.
 *
 * <p>{@code name} is the required display name of the unit (owner decision 2026-07-03): it is the
 * unit's single mandatory field, while the ship type and ship stay optional. {@code
 * responsibleUserId} optionally pins an explicit responsible person (otherwise the UI falls back to
 * the ship owner); {@code note} is a free-text planning note.
 */
public record AddUnitRequest(
    @NotBlank @Size(max = 255) String name,
    UUID shipTypeId,
    UUID shipId,
    Boolean highValueUnit,
    Double frequency,
    UUID responsibleUserId,
    @Size(max = 500) String note) {
  /**
   * Null-safe accessor for the HVU flag.
   *
   * @return {@code true} only when the caller explicitly flagged the unit as high-value
   */
  public boolean isHighValueUnit() {
    return highValueUnit != null && highValueUnit;
  }
}
