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

/**
 * Marker contract implemented by write-DTOs that carry a material reference and an amount and
 * therefore need to be validated by {@link ValidQuantityAmountValidator}.
 *
 * <p>Implementing this interface lets the validator pull both fields without reflection or
 * MapStruct introspection, and lets us put {@code @ValidQuantityAmount} on any DTO that exposes the
 * pair regardless of unrelated fields. The validator looks up the material's {@link
 * de.greluc.krt.profit.basetool.backend.model.QuantityType} and enforces either integer (PIECE) or
 * &le;3-decimal (SCU) precision on {@link #amount()}.
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
   * Returns the UUID of the referenced {@link de.greluc.krt.profit.basetool.backend.model.GameItem}
   * for a game-item payload (REQ-INV-029), or {@code null} for a material payload. Game-item
   * amounts are unconditionally positive whole units, so the validator needs no catalog lookup for
   * them. Default {@code null} keeps every existing material-only implementor source-compatible.
   *
   * @return the referenced game-item id, or {@code null} when the payload targets a material
   */
  default UUID gameItemId() {
    return null;
  }
}
