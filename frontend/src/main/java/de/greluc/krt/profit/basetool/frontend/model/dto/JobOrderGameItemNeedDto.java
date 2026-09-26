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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderGameItemNeedDto}: an ITEM order's outstanding need
 * for one game item, in whole units, labelling the item-mode allocation pickers (REQ-INV-039).
 *
 * @param gameItemId the game item this order still wants
 * @param orderedAmount whole units requested across the order's lines naming it
 * @param deliveredAmount whole units already handed over
 * @param allocatedAmount whole units of item stock currently earmarked to this order
 * @param outstandingAmount {@code ordered − delivered − allocated}, floored at 0; the figure to
 *     render
 */
public record JobOrderGameItemNeedDto(
    UUID gameItemId,
    Integer orderedAmount,
    Integer deliveredAmount,
    Integer allocatedAmount,
    Integer outstandingAmount) {}
