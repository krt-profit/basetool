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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A designated party that can receive requester funds for one order.
 *
 * @param id stable designation identity
 * @param userId current account link, when present
 * @param name current permitted label
 * @param active whether fresh handovers may use the designation
 * @param holderId the deliberately designated order-local holder, when present
 * @param correctable whether this is a direct designation that can still be corrected
 * @param correctsId original designation for an explicit correction
 * @param previousName previous recorded recipient label, when permitted
 * @param correctionName recorded replacement label, when permitted
 * @param correctedAt time the direct designation was corrected
 * @param correctionActorName label of the original recorder, when permitted
 */
public record JobOrderMoneyRecipientView(
    UUID id,
    @Nullable UUID userId,
    @Nullable String name,
    boolean active,
    @Nullable UUID holderId,
    boolean correctable,
    @Nullable UUID correctsId,
    @Nullable String previousName,
    @Nullable String correctionName,
    @Nullable Instant correctedAt,
    @Nullable String correctionActorName) {}
