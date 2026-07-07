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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Write payload for releasing one of the caller's own Lager rows to the Materialbörse
 * (REQ-MARKET-002). The caller supplies the source item and the trade remark; material, quality,
 * amount, owner and squadron are all derived server-side from the item (the client never sets them,
 * and the item's location is never read). If an active offer already exists for the item, the
 * release re-activates it and updates its remark rather than creating a duplicate.
 *
 * @param inventoryItemId the caller's Lager row to release; must belong to the caller.
 * @param remark the free-form Markdown trade remark, at most 20 000 characters (may be blank).
 */
public record MaterialExchangeReleaseRequest(
    @NotNull UUID inventoryItemId, @Size(max = 20000) String remark) {}
