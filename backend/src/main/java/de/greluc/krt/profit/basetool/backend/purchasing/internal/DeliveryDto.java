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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.DeliveryStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RejectReason;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One delivery of a deal (REQ-PUR-015, REQ-PUR-016).
 *
 * @param id the delivery
 * @param amount the amount the delegate handed over
 * @param location the handover location, or {@code null}
 * @param status the acceptance state
 * @param acceptedAmount the accepted part, or {@code null} while undecided
 * @param rejectedAmount the rejected part, or {@code null} while undecided
 * @param rejectReason why a part was rejected, or {@code null}
 * @param measuredQuality the quality measured on acceptance, or {@code null}
 * @param acceptedAt when the raising unit decided, or {@code null}
 * @param createdAt when the delegate recorded the delivery
 * @param canAccept whether the caller may decide it now
 * @param version the optimistic-locking version to echo
 */
public record DeliveryDto(
    UUID id,
    double amount,
    @Nullable PurchasingRefDto location,
    DeliveryStatus status,
    @Nullable Double acceptedAmount,
    @Nullable Double rejectedAmount,
    @Nullable RejectReason rejectReason,
    @Nullable Integer measuredQuality,
    @Nullable Instant acceptedAt,
    Instant createdAt,
    boolean canAccept,
    Long version) {}
