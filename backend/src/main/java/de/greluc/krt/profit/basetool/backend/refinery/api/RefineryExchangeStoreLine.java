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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One store line of a client's pickup, its receiver, unit and job order already decided by the
 * exchange's database rule (REQ-XCH-053, REQ-SEC-039).
 *
 * @param materialId the output material, one of the order's goods' outputs
 * @param locationId where the stock is booked, a location the Lager offers
 * @param quality the quality from 0 to 1000
 * @param amount the amount in the material's own unit, above zero
 * @param unit the material's own unit, the one {@code amount} is in
 * @param receiverId the member whose stock receives the line
 * @param orgUnitId the owning unit of the booked stock, or {@code null} for none
 * @param personal whether the stock is the receiver's private stock
 * @param jobOrderId the job order the stock is earmarked to, or {@code null}
 * @param note the note, or {@code null}
 */
public record RefineryExchangeStoreLine(
    @NotNull UUID materialId,
    @NotNull UUID locationId,
    int quality,
    double amount,
    @NotNull QuantityType unit,
    @NotNull UUID receiverId,
    @Nullable UUID orgUnitId,
    boolean personal,
    @Nullable UUID jobOrderId,
    @Nullable String note) {}
