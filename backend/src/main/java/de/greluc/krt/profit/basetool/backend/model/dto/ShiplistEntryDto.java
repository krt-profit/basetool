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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of a HangarXPLOR Shiplist JSON export, reduced to the fields the hangar import
 * consumes; unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShiplistEntryDto(
    @JsonProperty("name") String name,
    @JsonProperty("ship_name") String shipName,
    @JsonProperty("entity_type") String entityType,
    @JsonProperty("lti") Boolean lti) {}
