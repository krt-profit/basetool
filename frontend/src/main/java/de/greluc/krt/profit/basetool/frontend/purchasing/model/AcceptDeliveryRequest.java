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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code AcceptDeliveryRequest}; must match it field for field.
 *
 * @param acceptedAmount the accepted part
 * @param rejectedAmount the rejected part
 * @param rejectReason why a part is rejected
 * @param measuredQuality the measured quality, 0 to 1000
 * @param locationId where the accepted goods are stocked
 * @param version the echoed version of the delivery
 */
@DtoMirror
public record AcceptDeliveryRequest(
    @NotNull @PositiveOrZero Double acceptedAmount,
    @NotNull @PositiveOrZero Double rejectedAmount,
    @BackendEnumAsString String rejectReason,
    @Min(0) @Max(1000) Integer measuredQuality,
    UUID locationId,
    @NotNull Long version) {}
