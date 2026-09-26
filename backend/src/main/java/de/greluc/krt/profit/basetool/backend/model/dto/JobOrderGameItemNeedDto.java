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

import java.util.UUID;

/**
 * One ITEM order's outstanding need for a single game item, labelling the item-mode allocation
 * pickers (REQ-INV-039).
 *
 * <p>The outstanding figure is {@code ordered − delivered − allocated} in whole units; manufactured
 * units are already counted as allocated.
 *
 * @param gameItemId the game item the picker filters on
 * @param orderedAmount whole units requested across the order's lines for this item
 * @param deliveredAmount whole units already handed over on those lines
 * @param allocatedAmount whole units currently earmarked to this order, summed over allocation
 *     slices
 * @param outstandingAmount {@code orderedAmount − deliveredAmount − allocatedAmount}, floored at 0
 *     because earmarks may exceed what the order still wants
 */
public record JobOrderGameItemNeedDto(
    UUID gameItemId,
    Integer orderedAmount,
    Integer deliveredAmount,
    Integer allocatedAmount,
    Integer outstandingAmount) {}
