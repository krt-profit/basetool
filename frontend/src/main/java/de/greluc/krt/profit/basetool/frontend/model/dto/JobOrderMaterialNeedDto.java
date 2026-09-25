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
 * Frontend mirror of the backend {@code JobOrderMaterialNeedDto}: an order's outstanding need for
 * one {@code (material, quality)} bucket, labelling the Lager allocation pickers (REQ-INV-039).
 * Present only when requested with {@code withNeeds=true}.
 *
 * @param materialId the bucket's material
 * @param qualityFloor the quality floor ({@code 650}) or {@code null} for no floor
 * @param requiredAmount the order's outstanding requirement for the bucket
 * @param bookedAmount the inventory already linked to this order at or above the floor
 * @param outstandingAmount {@code requiredAmount − bookedAmount}, floored at 0; the figure to
 *     render
 */
public record JobOrderMaterialNeedDto(
    UUID materialId,
    Integer qualityFloor,
    Double requiredAmount,
    Double bookedAmount,
    Double outstandingAmount) {}
