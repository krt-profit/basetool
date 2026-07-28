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

package de.greluc.krt.profit.basetool.backend.dto.uex;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Inbound JSON record for UEX Corp's <code>/terminals</code> endpoint. Mapped to the project's own
 * {@code Terminal} entity by {@code UexUniverseSyncService}; downstream code consumes the entity,
 * not this DTO.
 */
@Builder
public record UexTerminalDto(
    @JsonProperty("is_available") Integer isAvailable,
    @JsonProperty("is_visible") Integer isVisible,
    @JsonProperty("is_jump_point") Integer isJumpPoint,
    @JsonProperty("has_loading_dock") Integer hasLoadingDock,
    @JsonProperty("has_docking_port") Integer hasDockingPort,
    @JsonProperty("has_freight_elevator") Integer hasFreightElevator,
    @JsonProperty("is_auto_load") Integer isAutoLoad,
    @JsonProperty("id") Integer id,
    @JsonProperty("name") String name,
    @JsonProperty("code") String code,
    @JsonProperty("type") String type,
    @JsonProperty("is_available_live") Integer isAvailableLive,
    @JsonProperty("nickname") String nickname,
    @JsonProperty("star_system_name") String starSystemName,
    @JsonProperty("planet_name") String planetName,
    @JsonProperty("orbit_name") String orbitName,
    @JsonProperty("moon_name") String moonName,
    @JsonProperty("space_station_name") String spaceStationName,
    @JsonProperty("outpost_name") String outpostName,
    @JsonProperty("city_name") String cityName,
    @JsonProperty("faction_name") String factionName,
    @JsonProperty("company_name") String companyName) {
  /** Returns {@code true} iff UEX reports the terminal as currently reachable in-game. */
  public Boolean checkIsAvailableLive() {
    return isAvailableLive != null && isAvailableLive == 1;
  }
}
