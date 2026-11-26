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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Repository projection of one non-hidden terminal that buys a material at a positive price.
 *
 * @param materialId the material
 * @param terminalName the terminal
 * @param priceSell the sale price in aUEC per SCU; always positive
 */
public record MaterialSaleRow(UUID materialId, String terminalName, BigDecimal priceSell) {}
