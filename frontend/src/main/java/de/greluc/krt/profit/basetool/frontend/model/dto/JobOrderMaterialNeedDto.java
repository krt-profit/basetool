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
 * Frontend mirror of the backend {@code JobOrderMaterialNeedDto}: one order's outstanding need for
 * a single {@code (material, quality)} bucket, used to label the Lager's allocation picker options
 * (REQ-INV-039).
 *
 * <p>Only populated when the page asked the lookup for it ({@code withNeeds=true}); the pickers
 * that render no figure receive an empty list. {@code qualityFloor} is the numeric inventory
 * quality the bucket's stock is summed at or above, so the Einbuchen form can compare it against
 * the grade being entered without re-deriving the 650 constant client-side.
 *
 * @param materialId the bucket's material
 * @param qualityFloor the quality floor ({@code 650}) or {@code null} for no floor
 * @param requiredAmount the order's outstanding requirement for the bucket
 * @param bookedAmount the inventory already linked to this order at or above the floor
 * @param outstandingAmount {@code requiredAmount − bookedAmount}, floored at 0 — what to render
 */
public record JobOrderMaterialNeedDto(
    UUID materialId,
    Integer qualityFloor,
    Double requiredAmount,
    Double bookedAmount,
    Double outstandingAmount) {}
