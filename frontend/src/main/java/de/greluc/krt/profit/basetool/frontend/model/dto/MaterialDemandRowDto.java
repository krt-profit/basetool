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
 * Frontend mirror of the backend {@code MaterialDemandRowDto}: one material at one quality, summed
 * across every non-terminal order of one responsible org unit (REQ-ORDERS-034).
 *
 * <p>{@code outstandingAmount} subtracts booked stock but not claims, which are only promises.
 *
 * @param material the bucket's material (carries {@code quantityType} for unit-aware display)
 * @param qualityRequirement the quality bucket name ({@code GOOD} or {@code NONE})
 * @param requiredAmount the summed outstanding demand across the group's orders
 * @param bookedAmount the summed inventory linked to those orders for this bucket
 * @param claimedAmount the summed claims lodged on those orders' buckets
 * @param outstandingAmount {@code requiredAmount − bookedAmount}, floored at 0 — what still has to
 *     be gathered
 * @param orders the contributing orders and their shares, for the row's drill-down
 */
public record MaterialDemandRowDto(
    MaterialDto material,
    @BackendEnumAsString String qualityRequirement,
    Double requiredAmount,
    Double bookedAmount,
    Double claimedAmount,
    Double outstandingAmount,
    List<MaterialDemandOrderShareDto> orders) {}
