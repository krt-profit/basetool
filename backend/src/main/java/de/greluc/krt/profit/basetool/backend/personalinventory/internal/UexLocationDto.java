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

package de.greluc.krt.profit.basetool.backend.personalinventory.internal;

/**
 * Combined search result entry exposing UEX cities and space stations to the frontend for the
 * personal-inventory location typeahead. The numeric {@code uexId} is the value persisted in {@code
 * personal_inventory_item.location_uex_id} and must be paired with {@link #type()} to identify the
 * location unambiguously.
 */
public record UexLocationDto(
    Integer uexId,
    PersonalInventoryLocationType type,
    String name,
    String starSystemName,
    String parentName) {}
