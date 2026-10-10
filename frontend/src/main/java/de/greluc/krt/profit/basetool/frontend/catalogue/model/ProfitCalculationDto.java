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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Frontend mirror of the backend's profit-calculation row for one material and the chosen ship,
 * relayed to the profit-calculation page's script.
 *
 * @param materialId the material's id
 * @param materialName the material's display name
 * @param minBuyPrice the lowest purchase price per SCU
 * @param maxSellPrice the highest sale price per SCU
 * @param profitPerScu the profit per SCU between the two
 * @param marginPercent the margin in percent
 * @param fullLoadCost the cost of a full load of the ship
 * @param maxProfitFullLoad the profit of a full load of the ship
 * @param buyTerminalName the cheapest buying terminal
 * @param buyTerminalLocation that terminal's location
 * @param sellTerminalName the best selling terminal
 * @param sellTerminalLocation that terminal's location
 */
@DtoMirror
public record ProfitCalculationDto(
    UUID materialId,
    String materialName,
    BigDecimal minBuyPrice,
    BigDecimal maxSellPrice,
    BigDecimal profitPerScu,
    BigDecimal marginPercent,
    BigDecimal fullLoadCost,
    BigDecimal maxProfitFullLoad,
    String buyTerminalName,
    String buyTerminalLocation,
    String sellTerminalName,
    String sellTerminalLocation) {}
