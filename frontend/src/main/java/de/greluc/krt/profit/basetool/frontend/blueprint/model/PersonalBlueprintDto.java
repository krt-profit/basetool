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

package de.greluc.krt.profit.basetool.frontend.blueprint.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirror of the backend {@code PersonalBlueprintResponse}: one blueprint the calling user owns,
 * without the owner's subject.
 *
 * @param id entry id
 * @param productKey normalized product key
 * @param productName canonical display name
 * @param outputItemId resolved output {@code game_item} id, or {@code null}
 * @param acquiredAt in-game acquisition time, or {@code null}
 * @param note free-form note, or {@code null}
 * @param removable whether the owner may delete this entry; {@code false} for an auto-granted
 *     default blueprint
 * @param version optimistic-lock version echoed back on update
 * @param createdAt row creation timestamp
 * @param updatedAt row last-update timestamp
 * @param source where the entry came from ({@code LOG}, {@code MANUAL}, {@code IMPORT}, {@code
 *     DEFAULT}, {@code OTHER}), or {@code null} when that was not recorded
 * @param sourceClientId the exchange client that added it, or {@code null}
 * @param sourceClientName the registry display name of {@code sourceClientId}, or {@code null} when
 *     there is no client or it is no longer registered
 */
@DtoMirror
public record PersonalBlueprintDto(
    UUID id,
    String productKey,
    String productName,
    UUID outputItemId,
    Instant acquiredAt,
    String note,
    boolean removable,
    Long version,
    Instant createdAt,
    Instant updatedAt,
    String source,
    String sourceClientId,
    String sourceClientName) {}
