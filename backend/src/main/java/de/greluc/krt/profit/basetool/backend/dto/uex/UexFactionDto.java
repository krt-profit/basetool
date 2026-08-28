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
 * Inbound JSON record for UEX Corp's <code>/factions</code> endpoint. Mapped to the project's own
 * {@code Faction} entity by {@code UexUniverseSyncService}; downstream code consumes the entity,
 * not this DTO.
 */
@Builder
public record UexFactionDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("name") String name,
    @JsonProperty("wiki") String wiki,
    @JsonProperty("is_piracy") Integer isPiracy,
    @JsonProperty("is_bounty_hunting") Integer isBountyHunting) {
  /** Returns {@code true} iff the faction is flagged as piracy-aligned by UEX. */
  public Boolean checkIsPiracy() {
    return isPiracy != null && isPiracy == 1;
  }

  /** Returns {@code true} iff the faction is flagged as bounty-hunting by UEX. */
  public Boolean checkIsBountyHunting() {
    return isBountyHunting != null && isBountyHunting == 1;
  }
}
