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
 * One order's outstanding need for a {@code (material, quality)} bucket, labelling the Lager's
 * allocation pickers (REQ-INV-039); consistent with the material-demand overview.
 *
 * @param materialId the bucket's material
 * @param qualityFloor the minimum quality counted toward the bucket (650 for {@code GOOD}), or
 *     {@code null} for no floor
 * @param requiredAmount the order's outstanding requirement for the bucket
 * @param bookedAmount the inventory linked to this order at or above {@code qualityFloor}
 * @param outstandingAmount {@code requiredAmount − bookedAmount}, floored at 0; ignores claims
 */
public record JobOrderMaterialNeedDto(
    UUID materialId,
    Integer qualityFloor,
    Double requiredAmount,
    Double bookedAmount,
    Double outstandingAmount) {}
