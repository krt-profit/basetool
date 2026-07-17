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
 * Frontend view of one Materialbörse release-picker entry — a Lager row of the caller's own stock
 * eligible for release. Deserialized from {@code /api/v1/material-exchange/releasable-items}.
 * Carries both a material row ({@link #kind} {@code MATERIAL}) and a game-item row ({@link #kind}
 * {@code ITEM}, a stock-backed item offer, REQ-MARKET-014).
 *
 * @param inventoryItemId the Lager row to release.
 * @param kind which offer kind releasing this row produces ({@code MATERIAL} / {@code ITEM}).
 * @param materialName the display name of the row's catalog entry (material name or game-item
 *     name).
 * @param quantityType the row's quantity unit name ({@code SCU} / {@code PIECE}); the picker JS
 *     renders the amount in this unit instead of always SCU.
 * @param quality the row's quality (0–1000); {@code null} for a game-item row (items have no
 *     quality).
 * @param amount the row's quantity, expressed in the row's {@link #quantityType} unit.
 * @param locationName the row's location (shown only in the owner's own picker).
 * @param alreadyReleased whether an active offer already exists for this row.
 */
public record MaterialExchangeReleasableItemDto(
    UUID inventoryItemId,
    @BackendEnumAsString String kind,
    String materialName,
    @BackendEnumAsString String quantityType,
    Integer quality,
    Double amount,
    String locationName,
    boolean alreadyReleased) {}
