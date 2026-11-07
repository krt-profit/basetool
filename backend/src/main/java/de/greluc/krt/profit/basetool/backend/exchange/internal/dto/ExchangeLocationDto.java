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
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A non-hidden Lager location for external clients, in the shape of the published {@code
 * location.schema.json} (REQ-XCH-018).
 *
 * @param name the location's curated name, never a starmap game text
 * @param uex the UEX place the location stands for, or {@code null} when it has none
 * @param guid the GUID of the P4K starmap object the location stands for, or {@code null} when it
 *     links none (REQ-P4K-037)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeLocationDto(String name, @Nullable UexRef uex, @Nullable UUID guid) {

  /**
   * A UEX place.
   *
   * @param kind {@code CITY} or {@code SPACE_STATION}
   * @param id the UEX id
   */
  public record UexRef(String kind, int id) {}
}
