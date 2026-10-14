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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code DeliveryDto}; must match it field for field.
 *
 * @param id the delivery
 * @param amount the amount handed over
 * @param location the handover location, or {@code null}
 * @param status the acceptance state
 * @param acceptedAmount the accepted part, or {@code null}
 * @param rejectedAmount the rejected part, or {@code null}
 * @param rejectReason why a part was rejected, or {@code null}
 * @param measuredQuality the measured quality, or {@code null}
 * @param acceptedAt when it was decided, or {@code null}
 * @param createdAt when it was recorded
 * @param canAccept whether the caller may decide it now
 * @param version the optimistic-locking version
 */
@DtoMirror
public record DeliveryDto(
    UUID id,
    double amount,
    PurchasingRefDto location,
    @BackendEnumAsString String status,
    Double acceptedAmount,
    Double rejectedAmount,
    @BackendEnumAsString String rejectReason,
    Integer measuredQuality,
    Instant acceptedAt,
    Instant createdAt,
    boolean canAccept,
    Long version) {}
