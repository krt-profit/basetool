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

import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import java.util.UUID;

/**
 * Per-material craftability breakdown for one material a blueprint consumes, with every
 * availability and quality figure given for inventory alone and with the open refinery yield.
 *
 * <p>All {@code *Scu} quantities are in the material's {@link QuantityType} (whole pieces for
 * {@link QuantityType#PIECE}). Only stock at or above {@code qualityFloor} counts (REQ-INV-048).
 *
 * @param materialId the commodity's id
 * @param materialName the commodity's display name
 * @param requiredScu amount one craft needs, in the {@link #quantityType} unit
 * @param qualityFloor lowest qualifying quality; {@code 0} when unconstrained
 * @param availableScu qualifying amount in "My Inventory" alone
 * @param availableScuWithRefinery qualifying amount including the open refinery yield
 * @param effectiveQuality quantity-weighted quality of the stock consumed for one craft, or {@code
 *     null} when no qualifying stock exists
 * @param effectiveQualityWithRefinery the same including the refinery yield, or {@code null}
 * @param missingScu amount short of one craft from inventory alone; {@code 0} when sufficient
 * @param missingScuWithRefinery amount short of one craft including the refinery yield
 * @param craftable crafts this material alone allows from inventory
 * @param craftableWithRefinery the same including the refinery yield
 * @param quantityType the material's quantity unit ({@code SCU} or {@code PIECE})
 */
public record CraftabilityMaterialDto(
    UUID materialId,
    String materialName,
    double requiredScu,
    int qualityFloor,
    double availableScu,
    double availableScuWithRefinery,
    Double effectiveQuality,
    Double effectiveQualityWithRefinery,
    double missingScu,
    double missingScuWithRefinery,
    int craftable,
    int craftableWithRefinery,
    QuantityType quantityType) {}
