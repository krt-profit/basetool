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
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The job orders and missions an entry of a unit's pool may be earmarked to, as the web's „+
 * Zuordnen" picker offers them, described by {@code org-stock-targets.schema.json} (REQ-XCH-045).
 *
 * @param unit the unit
 * @param jobOrders the open and in-progress job orders that need the material or item
 * @param missions the missions; none for an item
 */
public record ExchangeOrgStockTargetsDto(
    @NotNull ExchangeOrgStockUnitDto unit,
    @NotNull @Unmodifiable List<JobOrder> jobOrders,
    @NotNull @Unmodifiable List<Mission> missions) {

  /**
   * One job order.
   *
   * @param id the job order
   * @param number its running number, shown as {@code #number}, or {@code null} when it has none
   * @param status its status
   */
  @Schema(name = "OrgStockTargetJobOrder")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record JobOrder(@NotNull UUID id, @Nullable Integer number, @NotNull String status) {}

  /**
   * One mission.
   *
   * @param id the mission
   * @param name its name, or {@code null} when it has none
   * @param status its status
   */
  @Schema(name = "OrgStockTargetMission")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Mission(@NotNull UUID id, @Nullable String name, @NotNull String status) {}
}
