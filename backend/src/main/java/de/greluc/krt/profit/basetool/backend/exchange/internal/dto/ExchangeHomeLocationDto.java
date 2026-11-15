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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A curated home location a member may choose, in the shape of {@code
 * patch-change.schema.json#/$defs/homeLocation} (REQ-PATCH-051): a location with the id a choice
 * echoes.
 *
 * @param id the location's id, which a choice names
 * @param name its curated name
 * @param system the star system of its city or station, or {@code null} when it names none
 * @param uex the UEX place it stands for, or {@code null} when it links none
 * @param guid the GUID of the starmap object it stands for, or {@code null} when it links none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeHomeLocationDto(
    @NotNull UUID id,
    @NotNull String name,
    @Nullable String system,
    @Nullable ExchangeLocationDto.UexRef uex,
    @Nullable UUID guid) {}
