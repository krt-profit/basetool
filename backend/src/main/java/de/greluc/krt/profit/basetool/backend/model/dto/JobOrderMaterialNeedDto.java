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
 * One order's outstanding need for a single {@code (material, quality)} bucket — the figure the
 * Lager's check-in and per-entry allocation pickers label an order option with (REQ-INV-039), so a
 * member splitting a haul across orders sees what each one still needs without opening it.
 *
 * <p>Shipped only on the {@code withNeeds=true} variant of the order lookup, and folded from the
 * <em>same</em> normalisation and the same batched stock index the cross-order material-demand
 * overview uses ({@link MaterialDemandRowDto}, REQ-ORDERS-034), so a picker figure always
 * reconciles with that page and with the order detail.
 *
 * <p>Both order kinds contribute: a {@code MATERIAL} order through its material lines, an {@code
 * ITEM} order through its blueprint-derived requirements. A material required at two quality levels
 * yields two entries, matching the bucket key the rest of the order domain uses.
 *
 * @param materialId the bucket's material — the id the picker keys its option filter on
 * @param qualityFloor the inventory quality this bucket's stock is summed at or above ({@code 650}
 *     for a {@code GOOD} bucket), or {@code null} when the requirement imposes no floor. Shipped as
 *     the numeric floor rather than the {@code QualityRequirement} enum so a client can compare it
 *     against the grade being booked in without re-deriving the 650 constant — an allocation is
 *     gated on the material alone, so stock below this floor may be earmarked here and will simply
 *     not reduce {@code outstandingAmount}
 * @param requiredAmount the order's <b>outstanding</b> requirement for the bucket: the material
 *     line's remaining {@code amount} for a {@code MATERIAL} order (handovers decrement it in
 *     place), or the not-yet-manufactured share for an {@code ITEM} order
 * @param bookedAmount the inventory already linked to this order for the bucket's material at or
 *     above {@code qualityFloor}; {@code 0.0} when nothing is linked
 * @param outstandingAmount {@code requiredAmount − bookedAmount}, floored at 0 — what the order
 *     still needs. Deliberately ignores material claims: a claim is a promise that has moved no
 *     stock (REQ-ORDERS-024), so it must not shrink the amount still to be gathered. This is
 *     <em>not</em> {@code JobOrderMaterialDto.openAmount}, which is {@code required − Σ claims}
 */
public record JobOrderMaterialNeedDto(
    UUID materialId,
    Integer qualityFloor,
    Double requiredAmount,
    Double bookedAmount,
    Double outstandingAmount) {}
