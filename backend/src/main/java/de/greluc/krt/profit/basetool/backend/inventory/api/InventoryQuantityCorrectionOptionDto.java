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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A currently editable connected physical placement for deliberate correction selection.
 *
 * @param id current stock row
 * @param version independently observed row version
 * @param locationName current physical location label
 * @param holderDisplayName current holder label
 * @param owningOrgUnitName current pool label, if present
 * @param personal whether the row is personal stock
 * @param amount current physical quantity
 * @param quality current material quality, if present
 */
public record InventoryQuantityCorrectionOptionDto(
    UUID id,
    Long version,
    String locationName,
    String holderDisplayName,
    @Nullable String owningOrgUnitName,
    boolean personal,
    Double amount,
    @Nullable Integer quality) {}
