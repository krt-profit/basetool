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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One of the member's own refinery orders in the exchange feed, without its owner, who is always
 * the member (REQ-XCH-051).
 *
 * @param id the order's Basetool id, the feed key the write ops name
 * @param createdBy the client marker, or {@code null} for an order created in the web
 * @param versions the counters of the order's two sections
 * @param status {@code OPEN}, {@code IN_PROGRESS}, {@code COMPLETED} or {@code CANCELED}
 * @param location the refinery's location
 * @param method the refining method, or {@code null} when none is set
 * @param startedAt when the run started, or {@code null}
 * @param durationMinutes the run's length in minutes, or {@code null}
 * @param endsAt when the run ends, or {@code null} while start or length is unset
 * @param pickedUpAt when a client reported the yield collected, or {@code null}
 * @param storedAt when the yield was booked into stock, or {@code null}
 * @param expenses the refining costs in aUEC, or {@code null} when not set
 * @param otherExpenses the other costs in aUEC, or {@code null} when not set
 * @param oreSales the raw-ore sales in aUEC, or {@code null} when not set
 * @param missionId the linked mission's id, or {@code null}
 * @param orgUnitId the owning org unit's id, or {@code null} for a personal order without one
 * @param goods the goods, ordered by row id
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeRefineryOrderDto(
    @NotNull String id,
    @Nullable Creator createdBy,
    @NotNull Versions versions,
    @NotNull String status,
    @NotNull ExchangeLocationDto location,
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
   * The connected application that created the order (REQ-REFINERY-027).
   *
   * @param clientId the client's id in the exchange registry
   * @param name the client's display name, or its id when the registry no longer holds it
   */
  public record Creator(@NotNull String clientId, @NotNull String name) {}

  /**
   * The section counters a write echoes (REQ-REFINERY-029).
   *
   * @param gameData the counter of the location, method, timer, status and goods
   * @param finances the counter of {@code expenses}, {@code otherExpenses} and {@code oreSales}
   */
  public record Versions(long gameData, long finances) {}

  /**
   * A refining method.
   *
   * @param code the method's code, or {@code null} when the catalogue has none
   * @param name the method's name
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Method(@Nullable String code, @NotNull String name) {}

  /**
   * One good of the order.
   *
   * @param id the good's row id, the id the goods ops name
   * @param input the raw input material
   * @param inputQuantity the input amount in units, 100 to the SCU
   * @param output the refined output material
   * @param outputQuantity the yield in units, 100 to the SCU
   * @param quality the quality from 0 to 1000
   */
  public record Good(
      @NotNull String id,
      @NotNull ExchangeItemRefDto input,
      int inputQuantity,
      @NotNull ExchangeItemRefDto output,
      int outputQuantity,
      int quality) {}
}
