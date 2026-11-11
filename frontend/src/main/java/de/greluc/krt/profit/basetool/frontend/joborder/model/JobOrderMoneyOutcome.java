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
 * A committed provision projected with current participant names.
 *
 * @param operationId the logical operation
 * @param holderId the credited order-local holder
 * @param amount the booked principal in whole aUEC
 * @param bankHandling whether Bank handling was selected
 * @param recordedAt when the provision was booked
 * @param holderName the current visible holder name
 * @param actorName the current visible actor name
 */
@DtoMirror
public record JobOrderMoneyOutcome(
    UUID operationId,
    UUID holderId,
    String amount,
    boolean bankHandling,
    Instant recordedAt,
    @Nullable String holderName,
    @Nullable String actorName) {}
