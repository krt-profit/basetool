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
 * Pledges a donation (REQ-SUP-007). With a need, the receiving unit and the catalogue entry follow
 * the need; otherwise the request names them. A pledge from the donor's Lager takes the entry and
 * the quality from the row and earmarks the amount on it.
 *
 * @param needId the need to credit, or {@code null}
 * @param receivingOrgUnitId the receiving unit, required without a need
 * @param materialId the material, or {@code null} for a game item or when the row or need names it
 * @param gameItemId the game item, or {@code null}
 * @param quality the quality of a free material pledge, {@code 0} when omitted
 * @param amount the pledged amount
 * @param sourceInventoryItemId the donor's Lager row to earmark, or {@code null} for a free pledge
 * @param anonymous whether the donor stays anonymous to the receiving unit
 */
public record PledgeRequest(
    @Nullable UUID needId,
    @Nullable UUID receivingOrgUnitId,
    @Nullable UUID materialId,
    @Nullable UUID gameItemId,
    @Nullable Integer quality,
    @NotNull @Positive Double amount,
    @Nullable UUID sourceInventoryItemId,
    boolean anonymous) {}
