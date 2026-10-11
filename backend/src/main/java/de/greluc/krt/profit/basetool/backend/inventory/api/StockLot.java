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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One lot of a member's Lager: the rows of one material or game item at one location, quality and
 * stolen marker, as the stock commands lock and book it (ADR-0229).
 *
 * @param material the material, or {@code null} for an item lot
 * @param gameItem the game item, or {@code null} for a material lot
 * @param location the location
 * @param quality the quality of a material lot, or {@code null}
 * @param stolen whether the lot holds stolen stock
 */
public record StockLot(
    @Nullable Material material,
    @Nullable GameItem gameItem,
    @NotNull Location location,
    @Nullable Integer quality,
    boolean stolen) {}
