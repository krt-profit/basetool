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

import java.util.List;

/**
 * Frontend mirror of the backend {@code ClaimBucketDto}: required, claimed and open amounts plus
 * the individual claims of one material bucket on a public SK order.
 *
 * @param material the bucket's material (carries {@code quantityType} for unit-aware display)
 * @param qualityRequirement the quality bucket name ({@code GOOD} or {@code NONE})
 * @param requiredAmount total amount the order needs for this bucket
 * @param claimedAmount total already claimed across all squadrons
 * @param openRemaining {@code requiredAmount − claimedAmount}, floored at 0
 * @param claims the individual per-squadron claims on this bucket
 */
public record ClaimBucketDto(
    MaterialDto material,
    @BackendEnumAsString String qualityRequirement,
    Double requiredAmount,
    Double claimedAmount,
    Double openRemaining,
    List<ClaimDto> claims) {}
