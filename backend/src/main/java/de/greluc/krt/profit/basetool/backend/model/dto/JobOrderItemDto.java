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
 * One ordered finished-item line of an item job order: the requested {@code gameItem}, the {@code
 * blueprint} chosen to produce it, the requested, already-manufactured and already-delivered unit
 * counts, and the snapshotted per-material requirements. {@code parentItemId} is non-null when the
 * line was adopted from another line's blueprint sub-assembly suggestion (provenance). The counts
 * hold the invariant {@code 0 <= deliveredAmount <= manufacturedAmount <= amount}.
 *
 * <p>{@code blueprintStale} flags the REQ-ORDERS-033 drift case: the chosen blueprint no longer
 * produces {@code gameItem}, because a later SC-Wiki sync re-pointed it at a different item. The
 * line's snapshotted {@code materials} then faithfully mirror a <em>foreign</em> recipe and must
 * not be trusted — re-saving the order repairs the line by re-picking a blueprint that still
 * produces the item.
 *
 * @param id the item-line primary key
 * @param gameItem the requested finished item
 * @param blueprint the recipe chosen for this line
 * @param amount requested whole-unit count
 * @param manufacturedAmount whole units already manufactured (production booked)
 * @param deliveredAmount whole units already handed over
 * @param parentItemId the parent line this was adopted from, or {@code null} for a top-level line
 * @param materials the snapshotted material requirements for this line
 * @param blueprintStale {@code true} when the chosen blueprint no longer outputs {@code gameItem},
 *     making the snapshotted materials untrustworthy (REQ-ORDERS-033)
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
