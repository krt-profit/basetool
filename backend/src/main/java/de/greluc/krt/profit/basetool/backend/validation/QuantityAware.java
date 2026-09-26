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

package de.greluc.krt.profit.basetool.backend.validation;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Contract for write DTOs that carry a catalog reference and an amount, validated by {@link
 * ValidQuantityAmountValidator} against the material's {@link
 * de.greluc.krt.profit.basetool.backend.model.QuantityType}.
 */
public interface QuantityAware {
  /**
   * Returns UUID of the referenced {@link de.greluc.krt.profit.basetool.backend.model.Material};
   * may be {@code null} during validation if {@code @NotNull} on the field hasn't fired yet.
   *
   * @return UUID of the referenced {@link de.greluc.krt.profit.basetool.backend.model.Material};
   *     may be {@code null} during validation if {@code @NotNull} on the field hasn't fired yet
   */
  UUID materialId();

  /**
   * Returns requested quantity; may be {@code null} during validation if {@code @NotNull} hasn't
   * fired yet.
   *
   * @return requested quantity; may be {@code null} during validation if {@code @NotNull} hasn't
   *     fired yet
   */
  Double amount();

  /**
   * Returns the referenced {@link de.greluc.krt.profit.basetool.backend.model.GameItem} id for a
   * game-item payload (REQ-INV-029), whose amounts must be positive whole units. Defaults to {@code
   * null}.
   *
   * @return the referenced game-item id, or {@code null} when the payload targets a material
   */
  @Nullable
  default UUID gameItemId() {
    return null;
  }
}
