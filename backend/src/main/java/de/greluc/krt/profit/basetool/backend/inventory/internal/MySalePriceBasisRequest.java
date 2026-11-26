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

import jakarta.validation.constraints.NotNull;

/**
 * Body of the write of a member's "Mein Lager" price basis (REQ-INV-072).
 *
 * @param salePriceBasis the new basis
 * @param version the setting's version the caller last read, 0 for a member without a row
 */
public record MySalePriceBasisRequest(
    @NotNull SalePriceBasis salePriceBasis, @NotNull Long version) {}
