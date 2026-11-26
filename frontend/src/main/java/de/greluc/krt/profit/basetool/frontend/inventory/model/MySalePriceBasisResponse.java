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

/**
 * The member's "Mein Lager" price basis as {@code GET} and {@code PUT
 * /api/v1/inventory/my-inventory/sale-price-basis} answer it (REQ-INV-072).
 *
 * @param salePriceBasis the basis in effect, the average without a choice
 * @param version the setting's version, 0 while the member has made no choice
 */
@DtoMirror
public record MySalePriceBasisResponse(SalePriceBasis salePriceBasis, long version) {}
