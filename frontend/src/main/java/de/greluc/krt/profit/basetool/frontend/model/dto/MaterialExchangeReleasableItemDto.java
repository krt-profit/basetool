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
 * Frontend view of one "Material anbieten" picker entry — a Lager row of the caller's own stock
 * eligible for release. Deserialized from {@code /api/v1/material-exchange/releasable-items}.
 *
 * @param inventoryItemId the Lager row to release.
 * @param materialName the material's name.
 * @param quality the row's quality (0–1000).
 * @param amount the row's quantity in SCU.
 * @param locationName the row's location (shown only in the owner's own picker).
 * @param alreadyReleased whether an active offer already exists for this row.
 */
public record MaterialExchangeReleasableItemDto(
    UUID inventoryItemId,
    String materialName,
    Integer quality,
    Double amount,
    String locationName,
    boolean alreadyReleased) {}
