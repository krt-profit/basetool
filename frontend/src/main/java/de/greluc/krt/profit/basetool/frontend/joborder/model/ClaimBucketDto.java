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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.List;

/**
 * Frontend mirror of the backend {@code ClaimBucketDto}: required, claimed and open amounts plus
 * the individual claims of one material bucket on a public SK order.
 *
 * @param material the bucket's material (carries {@code quantityType} for unit-aware display)
 * @param qualityRequirement the code of the bucket's quality tier
 * @param qualityTier the bucket's quality tier, with its labels and floor
 * @param requiredAmount total amount the order needs for this bucket
 * @param claimedAmount total already claimed across all squadrons
 * @param openRemaining {@code requiredAmount − claimedAmount}, floored at 0
 * @param claims the individual per-squadron claims on this bucket
 */
@DtoMirror
public record ClaimBucketDto(
    MaterialDto material,
    String qualityRequirement,
    QualityTierDto qualityTier,
    Double requiredAmount,
    Double claimedAmount,
    Double openRemaining,
    List<ClaimDto> claims) {}
