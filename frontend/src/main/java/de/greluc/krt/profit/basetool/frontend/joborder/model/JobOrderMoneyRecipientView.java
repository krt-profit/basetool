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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A designated recipient in the authorized order view.
 *
 * @param id the order-local recipient identity
 * @param userId the linked account, if any
 * @param name the visible recipient label
 * @param active whether the recipient can be selected for a new handover
 * @param holderId the corresponding holder, if any
 * @param correctable whether this direct designation can still be corrected
 * @param correctsId original designation for an explicit correction
 * @param previousName previous recorded recipient label
 * @param correctionName recorded replacement label
 * @param correctedAt time of the correction
 * @param correctionActorName visible label of the original recorder
 */
@DtoMirror
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
