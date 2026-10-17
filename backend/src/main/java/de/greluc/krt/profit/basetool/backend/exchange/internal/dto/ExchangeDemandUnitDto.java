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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * An org unit a detailed demand order names as responsible or requesting unit (REQ-XCH-047).
 *
 * @param id the unit's primary key, which the client keeps and echoes as an opaque string
 * @param name the unit's display name as the Basetool shows it
 */
public record ExchangeDemandUnitDto(@NotNull UUID id, @NotNull String name) {}
