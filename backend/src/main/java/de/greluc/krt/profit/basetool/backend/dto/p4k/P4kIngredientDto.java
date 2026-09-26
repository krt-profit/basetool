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

package de.greluc.krt.profit.basetool.backend.dto.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One ingredient line of a {@link P4kBlueprintDto}.
 *
 * <p>Informational only: the importer resolves existing {@code blueprint_ingredient} rows by their
 * stored UUIDs, not from this payload. Any field may be {@code null}.
 *
 * @param resourceGuid resource GUID for a RESOURCE line (= {@code material.scwiki_uuid})
 * @param itemGuid item GUID for an ITEM line (= {@code game_item.external_uuid})
 * @param quantityScu quantity in SCU for a RESOURCE line, or {@code null}
 * @param quantityUnits quantity in whole units for an ITEM line, or {@code null}
 * @param minQuality minimum quality tier the ingredient must have, or {@code null}
 * @param slot slot debug name, or {@code null}
 * @param slotKey slot localization key, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kIngredientDto(
    String resourceGuid,
    String itemGuid,
    Double quantityScu,
    Integer quantityUnits,
    Integer minQuality,
    String slot,
    String slotKey) {}
