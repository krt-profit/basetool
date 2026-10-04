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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One material's best trade route for a ship: the cheapest purchase, the best sale and the
 * terminals that offer them.
 *
 * @param materialId the material's id
 * @param materialName the material's name
 * @param minBuyPrice the lowest positive purchase price per SCU
 * @param maxSellPrice the highest positive sale price per SCU
 * @param profitPerScu {@code maxSellPrice - minBuyPrice}, negative for a loss
 * @param marginPercent the profit per SCU as a percentage of the purchase price
 * @param fullLoadCost the purchase price of a full load of the ship
 * @param maxProfitFullLoad the profit of a full load of the ship
 * @param buyTerminalName the terminal selling at {@code minBuyPrice}; the first by name on a tie
 * @param buyTerminalLocation that terminal's planet or star system, then its city, station or
 *     outpost; {@code null} when the terminal names no place
 * @param sellTerminalName the terminal buying at {@code maxSellPrice}; the first by name on a tie
 * @param sellTerminalLocation that terminal's location, built like {@code buyTerminalLocation}
 */
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
