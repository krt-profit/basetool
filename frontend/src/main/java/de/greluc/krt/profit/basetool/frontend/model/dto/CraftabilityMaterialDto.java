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
 * Frontend mirror of the backend {@code CraftabilityMaterialDto}: the craftability breakdown for
 * one material a blueprint consumes (ADR-0046). Every availability and quality figure is given
 * without and with the open refinery yield.
 *
 * @param materialId the material's id
 * @param materialName the material's display name
 * @param requiredScu the SCU one craft needs of this material
 * @param qualityFloor the lowest qualifying quality applied
 * @param availableScu qualifying SCU in "My Inventory" stock alone
 * @param availableScuWithRefinery qualifying SCU including the open refinery yield
 * @param effectiveQuality SCU-weighted quality from inventory alone, or {@code null}
 * @param effectiveQualityWithRefinery the same including the open refinery yield, or {@code null}
 * @param missingScu amount short of one craft from inventory alone (in the {@code quantityType}
 *     unit)
 * @param missingScuWithRefinery amount short of one craft including refinery yield
 * @param craftable crafts this material alone allows from inventory
 * @param craftableWithRefinery crafts this material alone allows including refinery yield
 * @param quantityType the unit of the {@code *Scu} figures, {@code "SCU"} or {@code "PIECE"}
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
    @BackendEnumAsString String quantityType) {}
