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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One of the member's own ships in the exchange feed; purchase data is never sent (REQ-XCH-017).
 * Absent optional fields are left out.
 *
 * @param shipId the opaque ship id
 * @param externalId the calling installation's id for the ship, or {@code null} until it links one
 * @param version the version an update or removal echoes
 * @param shipType the ship type, with its id as {@code bt}
 * @param name the member's name for the ship, or {@code null} when it has none
 * @param insurance the insurance
 * @param location the location, or {@code null} when the ship has none
 * @param fitted whether the ship is fitted
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeShipDto(
    @NotNull String shipId,
    @Nullable String externalId,
    long version,
    @NotNull ExchangeItemRefDto shipType,
    @Nullable String name,
    @NotNull ExchangeInsuranceDto insurance,
    @Nullable ExchangeLocationDto location,
    boolean fitted) {}
