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

import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import java.util.List;

/**
 * Claim view of one aggregated material bucket on a public SK order: the required amount, the
 * amount claimed across squadrons, the open remainder and the individual claims.
 *
 * @param material the bucket's material (with {@code quantityType} for unit-aware formatting)
 * @param qualityRequirement the quality bucket ({@code GOOD} or {@code NONE})
 * @param requiredAmount total amount the order needs for this bucket
 * @param claimedAmount total already claimed across all squadrons
 * @param openRemaining {@code requiredAmount − claimedAmount}, floored at 0
 * @param claims the individual per-squadron claims on this bucket
 */
public record ClaimBucketDto(
    MaterialDto material,
    QualityRequirement qualityRequirement,
    Double requiredAmount,
    Double claimedAmount,
    Double openRemaining,
    List<ClaimDto> claims) {}
