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

import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;

/**
 * An amount with its unit: SCU with at most three decimals, or whole pieces (REQ-XCH-016).
 *
 * @param amount the amount
 * @param unit {@code SCU} or {@code PIECE}
 */
public record ExchangeQuantityDto(@NotNull BigDecimal amount, @NotNull String unit) {}
