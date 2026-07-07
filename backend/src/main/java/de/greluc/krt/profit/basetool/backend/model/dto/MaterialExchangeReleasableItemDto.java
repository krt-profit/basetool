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

import java.util.UUID;

/**
 * One entry of the "Material anbieten" item picker — a Lager row of the caller's own stock that can
 * be released to the Materialbörse (REQ-MARKET-002). Only ever returned to the item's owner, so it
 * may include the {@link #locationName} for disambiguation (the owner's own stock); that location
 * is never carried onto the public board.
 *
 * @param inventoryItemId the Lager row to release.
 * @param materialName the material's name.
 * @param quality the row's quality (0–1000).
 * @param amount the row's quantity in SCU.
 * @param locationName the row's location, shown only in the owner's own picker.
 * @param alreadyReleased whether an active offer already exists for this row.
 */
public record MaterialExchangeReleasableItemDto(
    UUID inventoryItemId,
    String materialName,
    Integer quality,
    Double amount,
    String locationName,
    boolean alreadyReleased) {}
