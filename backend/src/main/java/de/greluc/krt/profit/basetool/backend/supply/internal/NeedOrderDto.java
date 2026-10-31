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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The job order a need is linked to and how far its order-linked stock covers the need's material
 * and tier (REQ-SUP-023). The coverage is a hint only; it never counts toward the need's progress.
 *
 * @param id the job order
 * @param displayId the order's number
 * @param handle the order's short name, or {@code null}
 * @param status the order's status
 * @param requiredAmount the order's outstanding requirement for the need's material and tier, or
 *     {@code null} when the order is closed or does not require them
 * @param bookedAmount the order-linked stock attributed to that requirement, or {@code null} with
 *     {@code requiredAmount}
 */
public record NeedOrderDto(
    UUID id,
    @Nullable Integer displayId,
    @Nullable String handle,
    JobOrderStatus status,
    @Nullable Double requiredAmount,
    @Nullable Double bookedAmount) {}
