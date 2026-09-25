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
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderItemProductionCreateDto}: the production booking
 * posted by the "Herstellung" modal.
 *
 * @param amount the whole units manufactured in this booking
 * @param version the ordered item line's optimistic-lock version
 * @param consumption the per-inventory-entry material draws
 * @param skippedMaterialIds required materials marked "nicht ausbuchen", whose demand is excluded
 *     and whose stock is not consumed
 * @param bookIn where and for whom the produced units are booked into the Lager (REQ-INV-032), or
 *     {@code null} to create no stock
 */
public record JobOrderItemProductionCreateDto(
    Integer amount,
    Long version,
    List<JobOrderItemProductionConsumptionDto> consumption,
    List<UUID> skippedMaterialIds,
    BookInDto bookIn) {

  /**
   * Frontend mirror of the backend {@code JobOrderItemProductionCreateDto.BookInDto}: the Lager
   * target for produced units.
   *
   * @param locationId the storage location the produced units are booked in at
   * @param ownerUserId the user the stock row is created for; {@code null} defaults to the acting
   *     user
   * @param owningOrgUnitId the org-unit pool the row is stamped onto; {@code null} lets the backend
   *     stamp it
   * @param personal {@code true} books the units into the owner's personal pool ({@code null} =
   *     {@code false})
   * @param allocateToOrder {@code true} (also the {@code null} default) earmarks the units to the
   *     producing order; must be {@code false} when {@code personal} is set
   */
  public record BookInDto(
      UUID locationId,
      UUID ownerUserId,
      UUID owningOrgUnitId,
      Boolean personal,
      Boolean allocateToOrder) {}
}
