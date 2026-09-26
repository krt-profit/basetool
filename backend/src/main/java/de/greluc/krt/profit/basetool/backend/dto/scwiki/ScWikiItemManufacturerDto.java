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

package de.greluc.krt.profit.basetool.backend.dto.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * Manufacturer reference nested inside a {@link ScWikiItemDto}; bound but not written to {@code
 * game_item}.
 *
 * @param uuid Wiki manufacturer UUID
 * @param name manufacturer display name
 * @param code manufacturer short code (e.g. {@code "RSI"})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiItemManufacturerDto(UUID uuid, String name, String code) {}
