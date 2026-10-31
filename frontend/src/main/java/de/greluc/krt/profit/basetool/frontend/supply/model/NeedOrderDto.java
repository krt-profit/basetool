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

package de.greluc.krt.profit.basetool.frontend.supply.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code NeedOrderDto}; must match it field for field. The coverage
 * is a hint only and never counts toward the need's progress (REQ-SUP-023).
 *
 * @param id the job order
 * @param displayId the order's number, or {@code null}
 * @param handle the order's short name, or {@code null}
 * @param status the order's status
 * @param requiredAmount the order's outstanding requirement for the need's material and tier, or
 *     {@code null} when the order is closed or does not require them
 * @param bookedAmount the order-linked stock attributed to that requirement, or {@code null}
 */
@DtoMirror
public record NeedOrderDto(
    UUID id,
    Integer displayId,
    String handle,
    @BackendEnumAsString String status,
    Double requiredAmount,
    Double bookedAmount) {

  /**
   * Whether the order's linked stock covers its whole requirement for the need's bucket.
   *
   * @return {@code true} when a requirement exists and the stock reaches it
   */
  public boolean covered() {
    return requiredAmount != null
        && bookedAmount != null
        && requiredAmount > 0
        && bookedAmount >= requiredAmount;
  }
}
