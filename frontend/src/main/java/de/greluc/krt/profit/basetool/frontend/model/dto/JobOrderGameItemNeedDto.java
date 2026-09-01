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
 * Frontend mirror of the backend {@code JobOrderGameItemNeedDto}: one ITEM order's outstanding need
 * for a single game item, used to label the item-mode allocation pickers (REQ-INV-039).
 *
 * <p>Not the material figure in pieces — {@code orderedAmount − deliveredAmount − allocatedAmount},
 * because an item line counts what was built and handed over separately from what is earmarked. All
 * counts are whole units and there is no quality floor: item rows carry no quality (REQ-INV-029).
 *
 * @param gameItemId the game item this order still wants
 * @param orderedAmount whole units requested across the order's lines naming it
 * @param deliveredAmount whole units already handed over
 * @param allocatedAmount whole units of item stock currently earmarked to this order
 * @param outstandingAmount {@code ordered − delivered − allocated}, floored at 0 — what to render.
 *     The floor guards against <b>over-earmarking</b> (nothing caps an earmark against the order's
 *     remaining need), not against a handover, which can never over-deliver
 */
public record JobOrderGameItemNeedDto(
    UUID gameItemId,
    Integer orderedAmount,
    Integer deliveredAmount,
    Integer allocatedAmount,
    Integer outstandingAmount) {}
