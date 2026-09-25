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

package de.greluc.krt.profit.basetool.backend.model.projection;

import java.util.UUID;

/**
 * One pooled stock slice for the blueprint craftability calculation: the caller's available SCU of
 * one {@link de.greluc.krt.profit.basetool.backend.model.Material} at one quality tier, summed
 * across locations.
 *
 * <p>Slices come from the caller's {@link
 * de.greluc.krt.profit.basetool.backend.model.InventoryItem} stock and, optionally, the yield of
 * their open {@link de.greluc.krt.profit.basetool.backend.model.RefineryGood} orders. The
 * calculator consumes best-quality slices first.
 *
 * @param materialId the commodity this slice holds; never {@code null}
 * @param quality the quality tier (0..1000) shared by every unit in this slice
 * @param totalScu the available amount in SCU, summed across locations for the material/quality
 *     pair
 */
public record OwnedStockSlice(UUID materialId, Integer quality, Double totalScu) {}
