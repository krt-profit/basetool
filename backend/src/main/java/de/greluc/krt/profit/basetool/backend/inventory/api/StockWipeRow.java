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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One stock row a patch wipe may select (REQ-PATCH-012), with the catalogue facts the selection
 * reads: exactly one of the material and the game item is set.
 *
 * @param id the row
 * @param holderId the member who holds the row
 * @param materialId the material of a material row, or {@code null}
 * @param materialType that material's type, or {@code null}
 * @param gameItemId the game item of an item row, or {@code null}
 * @param itemType that item's P4K type ({@code GameItem#wikiType}), or {@code null} when unknown
 * @param itemSubType that item's sub-type, or {@code null} when unknown
 * @param itemClassification that item's SC Wiki classification, or {@code null} when unknown
 * @param itemKind that item's kind, or {@code null}
 */
public record StockWipeRow(
    @NotNull UUID id,
    @NotNull UUID holderId,
    @Nullable UUID materialId,
    @Nullable MaterialType materialType,
    @Nullable UUID gameItemId,
    @Nullable String itemType,
    @Nullable String itemSubType,
    @Nullable String itemClassification,
    @Nullable GameItemKind itemKind) {}
