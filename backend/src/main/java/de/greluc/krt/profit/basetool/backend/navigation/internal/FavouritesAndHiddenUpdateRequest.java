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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Write DTO that replaces the favourites and the hidden pages together, for a change that moves a
 * page from one to the other. Both versions are checked in the one request.
 *
 * @param favourites the new favourites with the version the caller last saw
 * @param hidden the new hidden pages with the version the caller last saw
 */
public record FavouritesAndHiddenUpdateRequest(
    @NotNull @Valid FavouritesUpdateRequest favourites,
    @NotNull @Valid HiddenUpdateRequest hidden) {}
