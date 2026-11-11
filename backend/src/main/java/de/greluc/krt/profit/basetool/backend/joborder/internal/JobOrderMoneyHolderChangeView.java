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
 * A participant-readable change to the identity of an order holder.
 *
 * @param id identity change identifier
 * @param kind reason for the change
 * @param holderId original holder identifier
 * @param targetHolderId surviving holder identifier after a merge, if applicable
 * @param previousName label before the change, subject to erasure
 * @param name label resulting from the change, subject to erasure
 * @param recordedAt instant of the correction
 * @param actorName currently permitted label of the original recorder
 */
public record JobOrderMoneyHolderChangeView(
    UUID id,
    JobOrderMoneyHolderIdentityDto.Kind kind,
    UUID holderId,
    @Nullable UUID targetHolderId,
    @Nullable String previousName,
    @Nullable String name,
    Instant recordedAt,
    @Nullable String actorName) {}
