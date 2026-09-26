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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Create payload for a production booking against one ordered item line (REQ-ORDERS-025). The
 * {@code consumption} plan must exactly cover the demand of every non-skipped required material.
 *
 * @param amount the whole units manufactured (≥ 1, ≤ the line's remaining-to-manufacture)
 * @param version the ordered item line's optimistic-lock version
 * @param consumption the per-entry material draws; may be empty when nothing is to be consumed
 * @param skippedMaterialIds required materials not booked out; {@code null} means none
 * @param bookIn where and for whom the produced units are booked into the Lager (REQ-INV-032);
 *     required
 */
public record JobOrderItemProductionCreateDto(
    @NotNull @Min(1) Integer amount,
    @NotNull Long version,
    @NotNull List<@Valid JobOrderItemProductionConsumptionDto> consumption,
    List<UUID> skippedMaterialIds,
    @NotNull @Valid BookInDto bookIn) {

  /**
   * Production book-in target: location, owner, org-unit pool, personal flag and auto-earmark
   * opt-out (REQ-INV-032).
   *
   * @param locationId the storage location; required
   * @param ownerUserId the user the stock row is created for; {@code null} means the acting user
   * @param owningOrgUnitId the org-unit picker output; {@code null} auto-stamps
   * @param personal {@code true} books into the owner's personal pool; {@code null} means {@code
   *     false}; excludes {@code allocateToOrder}
   * @param allocateToOrder {@code true} or {@code null} earmarks the units to the producing order;
   *     must be {@code false} when {@code personal} is set
   */
  public record BookInDto(
      @NotNull UUID locationId,
      UUID ownerUserId,
      UUID owningOrgUnitId,
      Boolean personal,
      Boolean allocateToOrder) {}
}
