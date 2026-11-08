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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One refinery order as the exchange feed serves it, without its owner (REQ-XCH-051).
 *
 * @param id the order id, the feed key
 * @param status {@code OPEN}, {@code IN_PROGRESS}, {@code COMPLETED} or {@code CANCELED}
 * @param gameDataVersion the counter of the game-data section (REQ-REFINERY-029)
 * @param financesVersion the counter of the finances section (REQ-REFINERY-029)
 * @param createdByClient the exchange client that created the order, or {@code null} when the web
 *     did (REQ-REFINERY-027)
 * @param location the refinery's location
 * @param method the refining method, or {@code null} when none is set
 * @param startedAt when the run started, or {@code null}
 * @param durationMinutes the run's length in minutes, or {@code null}
 * @param endsAt {@code startedAt + durationMinutes}, or {@code null} when either is unset
 * @param pickedUpAt when an exchange client reported the pickup, or {@code null} (REQ-REFINERY-028)
 * @param storedAt when the yield was booked into stock, or {@code null}
 * @param expenses the refining costs in aUEC, or {@code null} when not set
 * @param otherExpenses the other costs in aUEC, or {@code null} when not set
 * @param oreSales the raw-ore sales in aUEC, or {@code null} when not set
 * @param missionId the linked mission, or {@code null}
 * @param orgUnitId the owning org unit, or {@code null} for an ownerless personal order
 * @param goods the order's goods
 */
public record RefineryExchangeOrder(
    @NotNull UUID id,
    @NotNull String status,
    long gameDataVersion,
    long financesVersion,
    @Nullable String createdByClient,
    @NotNull Place location,
    @Nullable Method method,
    @Nullable Instant startedAt,
    @Nullable Long durationMinutes,
    @Nullable Instant endsAt,
    @Nullable Instant pickedUpAt,
    @Nullable Instant storedAt,
    @Nullable Double expenses,
    @Nullable Double otherExpenses,
    @Nullable Double oreSales,
    @Nullable UUID missionId,
    @Nullable UUID orgUnitId,
    @NotNull @Unmodifiable List<Good> goods) {

  /**
   * The refinery's location with its UEX links.
   *
   * @param id the location id
   * @param name the location's name
   * @param uexCityId the UEX id of its city, or {@code null}
   * @param uexSpaceStationId the UEX id of its space station, or {@code null}
   */
  public record Place(
      @NotNull UUID id,
      @NotNull String name,
      @Nullable Integer uexCityId,
      @Nullable Integer uexSpaceStationId) {}

  /**
   * A refining method.
   *
   * @param code the method's code, or {@code null} when the catalogue has none
   * @param name the method's name
   */
  public record Method(@Nullable String code, @NotNull String name) {}

  /**
   * One good: an input material refined into an output material.
   *
   * @param id the good's row id
   * @param inputMaterialId the raw input material
   * @param inputMaterialName its name
   * @param inputQuantity the input amount in units, 100 to the SCU
   * @param outputMaterialId the refined output material
   * @param outputMaterialName its name
   * @param outputQuantity the yield in units, 100 to the SCU
   * @param quality the quality from 0 to 1000
   */
  public record Good(
      @NotNull UUID id,
      @NotNull UUID inputMaterialId,
      @NotNull String inputMaterialName,
      int inputQuantity,
      @NotNull UUID outputMaterialId,
      @NotNull String outputMaterialName,
      int outputQuantity,
      int quality) {}
}
