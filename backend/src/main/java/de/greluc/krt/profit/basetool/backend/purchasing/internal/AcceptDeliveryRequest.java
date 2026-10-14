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

import de.greluc.krt.profit.basetool.backend.purchasing.api.RejectReason;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Decides a delivery (REQ-PUR-016): the accepted part is booked into the raising unit's pool at the
 * named location, the rest is rejected.
 *
 * @param acceptedAmount the accepted part
 * @param rejectedAmount the rejected part; accepted plus rejected equal the delivery
 * @param rejectReason why a part is rejected, required when one is
 * @param measuredQuality the measured quality of a material, 0 to 1000
 * @param locationId where the accepted goods are stocked, required when something is accepted
 * @param version the echoed version of the delivery
 */
public record AcceptDeliveryRequest(
    @NotNull @PositiveOrZero Double acceptedAmount,
    @NotNull @PositiveOrZero Double rejectedAmount,
    @Nullable RejectReason rejectReason,
    @Nullable @Min(0) @Max(1000) Integer measuredQuality,
    @Nullable UUID locationId,
    @NotNull Long version) {}
