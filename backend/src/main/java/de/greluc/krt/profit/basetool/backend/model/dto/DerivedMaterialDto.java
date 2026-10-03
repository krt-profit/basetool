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

/**
 * One resolved material requirement in an item-order derivation preview: the material, the quantity
 * needed for the previewed amount (unit from {@code material.quantityType}), and the quality tier
 * the UI should pre-select: the active tier with the highest floor the blueprint ingredient's
 * {@code minQuality} reaches. The requester may override it per material before submitting.
 *
 * @param material the required material (carries {@code quantityType} for unit-aware display)
 * @param requiredQuantity quantity needed for the previewed amount
 * @param defaultQuality the code of the pre-selected quality tier
 * @param defaultQualityTier the pre-selected quality tier
 */
public record DerivedMaterialDto(
    MaterialDto material,
    Double requiredQuantity,
    String defaultQuality,
    QualityTierDto defaultQualityTier) {}
