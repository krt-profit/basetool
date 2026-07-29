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

import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code JobOrderItemDto}: one ordered finished-item line of an item
 * order.
 *
 * @param id the item-line id
 * @param gameItem the requested finished item
 * @param blueprint the chosen recipe
 * @param amount requested whole-unit count
 * @param manufacturedAmount whole units already manufactured (production booked)
 * @param deliveredAmount whole units already handed over
 * @param parentItemId the parent line this was adopted from, or {@code null}
 * @param materials the snapshotted material requirements
 * @param blueprintStale {@code true} when the chosen blueprint no longer produces {@code gameItem}
 *     after an SC-Wiki re-sync, making the snapshotted materials a foreign recipe (REQ-ORDERS-033)
 * @param version optimistic-lock version
 */
public record JobOrderItemDto(
    UUID id,
    GameItemReferenceDto gameItem,
    BlueprintReferenceDto blueprint,
    Integer amount,
    Integer manufacturedAmount,
    Integer deliveredAmount,
    UUID parentItemId,
    List<JobOrderItemMaterialDto> materials,
    boolean blueprintStale,
    Long version) {}
