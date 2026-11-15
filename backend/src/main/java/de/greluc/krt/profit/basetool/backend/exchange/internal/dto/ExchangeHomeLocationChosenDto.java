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

import org.jetbrains.annotations.NotNull;

/**
 * What a home-location choice through an exchange client moved, in the shape of {@code
 * patch-change.schema.json#/$defs/homeLocationChosen} (REQ-PATCH-052).
 *
 * @param location the chosen home location
 * @param rows how many of the member's stock rows moved there
 * @param ships how many of the member's ships moved there
 */
public record ExchangeHomeLocationChosenDto(
    @NotNull ExchangeHomeLocationDto location, int rows, int ships) {}
