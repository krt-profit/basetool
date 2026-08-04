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
 * Frontend mirror of the backend {@code MaterialDemandOrderShareDto}: one order's contribution to a
 * material bucket of the cross-order demand overview (REQ-ORDERS-034), rendered in the row's
 * drill-down. {@code status} and {@code type} are the enum names as strings, matching how the other
 * frontend mirrors carry backend enums.
 *
 * @param jobOrderId the contributing order's id, used to link to its detail page
 * @param displayId the order's human-readable sequential number
 * @param status the order's status name ({@code OPEN} or {@code IN_PROGRESS})
 * @param type the order kind name ({@code MATERIAL} or {@code ITEM}) this share came from
 * @param requiredAmount this order's outstanding required amount for the bucket
 * @param bookedAmount the inventory linked to this order for the bucket
 * @param claimedAmount the amount claimed on this order's bucket; {@code 0.0} for a non-SK order
 */
public record MaterialDemandOrderShareDto(
    UUID jobOrderId,
    Integer displayId,
    String status,
    String type,
    Double requiredAmount,
    Double bookedAmount,
    Double claimedAmount) {}
