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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import java.util.UUID;

/**
 * Read-only GROUP BY projection of the marked "Mein Lager" entries of one material with one
 * „gestohlen" marker, the input of the selection's sale value (REQ-INV-072).
 *
 * @param materialId the material every counted entry holds
 * @param quantityType the material's unit; a {@code PIECE} material carries no sale value
 * @param stolen whether the counted entries are marked „gestohlen" (REQ-INV-053)
 * @param amount the summed amount of the counted entries ({@code SUM(amount)}, null-coalesced)
 */
public record InventorySaleSlice(
    UUID materialId, QuantityType quantityType, Boolean stolen, Double amount) {}
