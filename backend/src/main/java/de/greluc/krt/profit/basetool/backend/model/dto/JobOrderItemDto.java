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

import java.util.List;
import java.util.UUID;

/**
 * One ordered finished-item line of an item job order, with its blueprint, unit counts and
 * snapshotted material requirements. The counts satisfy {@code 0 <= deliveredAmount <=
 * manufacturedAmount <= amount}.
 *
 * @param id the item-line primary key
 * @param gameItem the requested finished item
 * @param blueprint the recipe chosen for this line
 * @param amount requested whole-unit count
 * @param manufacturedAmount whole units already manufactured
 * @param deliveredAmount whole units already handed over
 * @param parentItemId the line this was adopted from, or {@code null} for a top-level line
 * @param materials the snapshotted material requirements for this line
 * @param blueprintStale {@code true} when the blueprint no longer outputs {@code gameItem}, so the
 *     snapshotted materials are untrustworthy (REQ-ORDERS-033)
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
