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
 * the "Herstellung" modal posts, relayed verbatim to the backend by {@code
 * JobOrderWriteController.bookProductionAjax}.
 *
 * @param amount the whole units manufactured in this booking
 * @param version the ordered item line's optimistic-lock version
 * @param consumption the per-inventory-entry material draws
 * @param skippedMaterialIds ids of required materials the operator marked "nicht ausbuchen" — their
 *     demand is excluded and their linked stock is not consumed
 * @param bookIn where and for whom the produced units are booked into the Lager as game-item stock
 *     (REQ-INV-032); {@code null} keeps the transitional legacy behaviour (no stock created) until
 *     the production modal ships the book-in section
 */
public record JobOrderItemProductionCreateDto(
    Integer amount,
    Long version,
    List<JobOrderItemProductionConsumptionDto> consumption,
    List<UUID> skippedMaterialIds,
    BookInDto bookIn) {

  /**
   * Frontend mirror of the backend {@code JobOrderItemProductionCreateDto.BookInDto}: the
   * production book-in target the modal will post (location, owner, org-unit pool, personal flag,
   * auto-earmark opt-out), relayed as-is.
   *
   * @param locationId the storage location the produced units are booked in at
   * @param ownerUserId the user the stock row is created for; {@code null} defaults to the acting
   *     user
   * @param owningOrgUnitId the org-unit picker output whose stock pool the row is stamped onto;
   *     {@code null} triggers the backend auto-stamp path
   * @param personal {@code true} books the units into the owner's personal pool ({@code null} =
   *     {@code false})
   * @param allocateToOrder {@code true} (also the {@code null} default) auto-earmarks the produced
   *     units to the producing order; must be {@code false} when {@code personal} is set
   */
  public record BookInDto(
      UUID locationId,
      UUID ownerUserId,
      UUID owningOrgUnitId,
      Boolean personal,
      Boolean allocateToOrder) {}
}
