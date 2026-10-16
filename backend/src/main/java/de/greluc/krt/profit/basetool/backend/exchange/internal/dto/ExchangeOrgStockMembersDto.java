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

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The roster of a unit a Logistician books in for: its direct members who do not withhold their
 * name, as {@code org-stock-members.schema.json} describes it (REQ-XCH-045).
 *
 * @param unit the unit
 * @param items the members, ordered by name
 */
public record ExchangeOrgStockMembersDto(
    @NotNull ExchangeOrgStockUnitDto unit, @NotNull @Unmodifiable List<Member> items) {

  /**
   * One member.
   *
   * @param ref the member's pseudonym at the calling client, as the org stock's entries carry it
   * @param name the name the web shows
   */
  @Schema(name = "OrgStockMember")
  public record Member(@NotNull String ref, @NotNull String name) {}
}
