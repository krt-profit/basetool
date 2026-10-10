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

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Frontend mirror of the backend's selling-terminal row of one material, relayed to the inventory
 * book-out modal's terminal picker.
 *
 * @param terminalId the terminal's id
 * @param terminalName the terminal's display name
 * @param priceSell the terminal's sale price per unit, or {@code null} when unknown
 */
@DtoMirror
public record MaterialSellingTerminalDto(
    UUID terminalId, String terminalName, BigDecimal priceSell) {}
