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
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload for releasing one of the caller's own Lager rows to the Materialbörse (REQ-MARKET-002).
 *
 * <p>Material, quality, owner and squadron are derived from the item. An existing active offer for
 * the item is updated instead of duplicated.
 *
 * @param inventoryItemId the caller's Lager row to release; must belong to the caller.
 * @param offeredAmount the SCU quantity to offer; positive and at most the item's current amount
 *     (ADR-0086).
 * @param remark the Markdown trade remark, at most 20 000 characters (may be blank).
 */
public record MaterialExchangeReleaseRequest(
    @NotNull UUID inventoryItemId,
    @NotNull @Positive Double offeredAmount,
    @Size(max = 20000) String remark) {}
