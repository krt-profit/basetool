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

import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import java.util.UUID;

/**
 * One order's share of a material bucket in the cross-order material-demand overview
 * (REQ-ORDERS-034) — the drill-down row a user expands to see <em>which</em> orders make up an
 * aggregated demand figure. Every component is this single order's contribution to the enclosing
 * {@link MaterialDemandRowDto}, so the row's totals are exactly the sum of its shares.
 *
 * @param jobOrderId the contributing order's surrogate id, so the row can link to its detail page
 * @param displayId the order's human-readable sequential number, the label a user recognises
 * @param status the order's status; always a non-terminal one ({@code OPEN} or {@code IN_PROGRESS})
 *     because the overview never aggregates completed or rejected orders
 * @param type the order kind this share came from — {@code MATERIAL} for a direct material line,
 *     {@code ITEM} for a blueprint-derived requirement — so the UI can explain why an order without
 *     visible material lines still contributes demand
 * @param requiredAmount this order's <b>outstanding</b> required amount for the bucket: the
 *     material line's remaining {@code amount} for a {@code MATERIAL} order (handovers already
 *     decrement it), or the not-yet-manufactured share for an {@code ITEM} order
 * @param bookedAmount the inventory linked to <em>this</em> order for the bucket's material at or
 *     above its quality floor; {@code 0.0} when nothing is linked
 * @param claimedAmount the amount squadrons have collectively claimed on this order's bucket;
 *     {@code 0.0} for a squadron-responsible order, which carries no claims
 */
public record MaterialDemandOrderShareDto(
    UUID jobOrderId,
    Integer displayId,
    JobOrderStatus status,
    JobOrderType type,
    Double requiredAmount,
    Double bookedAmount,
    Double claimedAmount) {}
