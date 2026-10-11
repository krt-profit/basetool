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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code InventoryItemStolenMarkDto}: sets or removes the
 * „gestohlen" marker on a Lager row or a part of it (REQ-INV-053).
 *
 * @param version the row version the page last rendered
 * @param stolen the requested marker
 * @param amount the part to change, split off as a new row; {@code null} changes the whole row
 */
@DtoMirror
public record InventoryItemStolenMarkDto(
    @Nullable Long version, @Nullable Boolean stolen, @Nullable Double amount) {}
