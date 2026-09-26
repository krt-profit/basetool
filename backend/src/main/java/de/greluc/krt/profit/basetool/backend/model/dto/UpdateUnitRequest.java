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
 * Request payload for a full-form edit of a mission unit, the versioned twin of {@link
 * AddUnitRequest}.
 *
 * <p>A present {@code version} that does not match the unit's current version yields 409, the only
 * guard against a stale form overwriting a concurrent edit; {@code null} skips the check via {@link
 * de.greluc.krt.profit.basetool.backend.support.OptimisticLock#checkOptionalClient}.
 */
public record UpdateUnitRequest(
    @NotBlank @Size(max = 255) String name,
    UUID shipTypeId,
    UUID shipId,
    Boolean highValueUnit,
    Double frequency,
    UUID responsibleUserId,
    @Size(max = 500) String note,
    Long version) {
  /**
   * Null-safe accessor for the HVU flag.
   *
   * @return {@code true} only when the caller explicitly flagged the unit as high-value
   */
  public boolean isHighValueUnit() {
    return highValueUnit != null && highValueUnit;
  }
}
