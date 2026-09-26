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
 * Frontend mirror of the backend {@code CreateClaimDto}: the create-or-update payload for a
 * material claim. {@code (materialId, qualityRequirement, claimingOrgUnitId)} identifies the claim;
 * re-posting updates its amount.
 *
 * @param materialId the material being claimed
 * @param qualityRequirement the quality bucket name ({@code GOOD} or {@code NONE})
 * @param claimingOrgUnitId the squadron making the claim
 * @param amount the claimed partial quantity (strictly positive)
 */
public record CreateClaimDto(
    UUID materialId,
    @BackendEnumAsString String qualityRequirement,
    UUID claimingOrgUnitId,
    Double amount) {}
