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

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One unit whose pool the member reads, in the shape of the published {@code org-unit.schema.json}
 * with its {@code logistician} member (REQ-XCH-042).
 *
 * @param id the unit's primary key, which the client keeps and echoes as an opaque string
 * @param name the unit's display name as the Basetool shows it
 * @param shorthand the unit's short label, never blank
 * @param kind whether it is a Staffel, a Spezialkommando, a Bereich or the Organisationsleitung
 * @param logistician whether the member is a Logistician of exactly this unit (REQ-XCH-043)
 */
public record ExchangeOrgStockPoolDto(
    @NotNull UUID id,
    @NotNull String name,
    @NotNull String shorthand,
    @NotNull OrgUnitKind kind,
    boolean logistician) {

  /**
   * Builds the pool entry of a unit the member directly belongs to.
   *
   * @param unit the unit
   * @param logistician whether the member is a Logistician of the unit
   * @return the pool entry
   */
  public static @NotNull ExchangeOrgStockPoolDto of(
      @NotNull ExchangeOrgUnitDto unit, boolean logistician) {
    return new ExchangeOrgStockPoolDto(
        unit.id(), unit.name(), unit.shorthand(), unit.kind(), logistician);
  }
}
