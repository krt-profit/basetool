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
 * Inbound JSON record for UEX Corp's <code>/jurisdictions</code> endpoint. Mapped to the project's
 * own {@code Jurisdiction} entity by {@code UexUniverseSyncService}; downstream code consumes the
 * entity, not this DTO.
 */
@Builder
public record UexJurisdictionDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("name") String name,
    @JsonProperty("is_available_live") Integer isAvailableLive,
    @JsonProperty("nickname") String nickname,
    @JsonProperty("wiki") String wiki,
    @JsonProperty("faction_name") String factionName) {
  /** Returns {@code true} iff UEX reports the jurisdiction as currently active in-game. */
  public Boolean checkIsAvailableLive() {
    return isAvailableLive != null && isAvailableLive == 1;
  }
}
