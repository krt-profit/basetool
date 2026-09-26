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

import de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOfferKind;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import java.util.UUID;

/**
 * One of the caller's own Lager rows in the Materialbörse release picker, either a material row or
 * a game-item row (REQ-MARKET-014).
 *
 * <p>Only returned to the row's owner; the location never reaches the public board.
 *
 * @param inventoryItemId the Lager row to release.
 * @param kind the offer kind releasing this row produces: {@code MATERIAL} or {@code ITEM}.
 * @param materialName the display name of the row's material or game item.
 * @param quantityType the row's quantity unit ({@code SCU} or {@code PIECE}); always {@code PIECE}
 *     for a game-item row.
 * @param quality the row's quality (0–1000) for a material row; {@code null} for a game-item row.
 * @param amount the row's quantity in its {@link #quantityType} unit.
 * @param locationName the row's location, shown only in the owner's own picker.
 * @param alreadyReleased whether an active offer already exists for this row.
 */
public record MaterialExchangeReleasableItemDto(
    UUID inventoryItemId,
    MaterialExchangeOfferKind kind,
    String materialName,
    QuantityType quantityType,
    Integer quality,
    Double amount,
    String locationName,
    boolean alreadyReleased) {}
