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
 * (REQ-ORDERS-034); a {@link MaterialDemandRowDto}'s totals are the sum of its shares.
 *
 * @param jobOrderId the contributing order's id
 * @param displayId the order's human-readable sequential number
 * @param status the order's status; always {@code OPEN} or {@code IN_PROGRESS}
 * @param type the order kind this share came from: {@code MATERIAL} or blueprint-derived {@code
 *     ITEM}
 * @param requiredAmount this order's outstanding required amount for the bucket
 * @param bookedAmount the inventory linked to this order for the bucket at or above its quality
 *     floor; {@code 0.0} when none
 * @param claimedAmount the amount claimed on this order's bucket; {@code 0.0} for a
 *     squadron-responsible order
 */
public record MaterialDemandOrderShareDto(
    UUID jobOrderId,
    Integer displayId,
    JobOrderStatus status,
    JobOrderType type,
    Double requiredAmount,
    Double bookedAmount,
    Double claimedAmount) {}
