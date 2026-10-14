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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Records a donation the receiving unit already received (REQ-SUP-011) and books it into the pool.
 *
 * @param receivingOrgUnitId the receiving unit
 * @param needId the need to credit, or {@code null}
 * @param materialId the material, or {@code null} for a game item or when the need names it
 * @param gameItemId the game item, or {@code null}
 * @param quality the quality of a material, {@code 0} when omitted
 * @param amount the received amount
 * @param donorUserId the donor, or {@code null} for a donation without one
 * @param locationId where the stock lies now
 */
public record RecordDonationRequest(
    @NotNull UUID receivingOrgUnitId,
    @Nullable UUID needId,
    @Nullable UUID materialId,
    @Nullable UUID gameItemId,
    @Nullable Integer quality,
    @NotNull @Positive Double amount,
    @Nullable UUID donorUserId,
    @NotNull UUID locationId) {}
