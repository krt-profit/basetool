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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.uex;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound JSON record for UEX Corp's refinery-yield endpoint: the yield bonus in percent of one
 * commodity at one refinery terminal; consumed by {@code UexRefinerySyncService}.
 *
 * @param id the UEX row id
 * @param idCommodity the UEX commodity id
 * @param idTerminal the UEX terminal id
 * @param value the current yield bonus in percent
 * @param valueWeek the average yield bonus of the last 7 days in percent, or {@code null}
 * @param valueMonth the average yield bonus of the last 30 days in percent, or {@code null}
 */
public record UexRefineryYieldDto(
    Integer id,
    @JsonProperty("id_commodity") Integer idCommodity,
    @JsonProperty("id_terminal") Integer idTerminal,
    Integer value,
    @JsonProperty("value_week") Integer valueWeek,
    @JsonProperty("value_month") Integer valueMonth) {}
