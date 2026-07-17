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
 * One entry of the Materialbörse release picker — a Lager row of the caller's own stock that can be
 * released to the board (REQ-MARKET-002/014). The picker carries <b>both</b> kinds of row: a
 * material row (releases a {@link MaterialExchangeOfferKind#MATERIAL} offer) and a game-item row
 * (releases a stock-backed {@link MaterialExchangeOfferKind#ITEM} offer, design §8), discriminated
 * by {@link #kind}. Only ever returned to the row's owner, so it may include the {@link
 * #locationName} for disambiguation (the owner's own stock); that location is never carried onto
 * the public board.
 *
 * @param inventoryItemId the Lager row to release.
 * @param kind which offer kind releasing this row produces — {@code MATERIAL} for a material row,
 *     {@code ITEM} for a game-item row.
 * @param materialName the display name of the row's catalog entry — the material name for a
 *     material row, the game-item name for an item row.
 * @param quantityType the row's quantity unit ({@code SCU} or {@code PIECE}), so the picker and the
 *     release dialog render the amount in the row's own unit; always {@code PIECE} for a game-item
 *     row (items are whole units).
 * @param quality the row's quality (0–1000) for a material row; {@code null} for a game-item row
 *     (items have no quality).
 * @param amount the row's quantity, expressed in the row's {@link #quantityType} unit.
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
