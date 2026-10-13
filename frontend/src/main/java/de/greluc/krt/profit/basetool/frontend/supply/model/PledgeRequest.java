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

package de.greluc.krt.profit.basetool.frontend.supply.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code PledgeRequest}; must match it field for field.
 *
 * @param needId the need to credit, or {@code null}
 * @param receivingOrgUnitId the receiving unit, required without a need
 * @param materialId the material, or {@code null}
 * @param gameItemId the game item, or {@code null}
 * @param quality the quality of a free material pledge, or {@code null}
 * @param amount the pledged amount
 * @param sourceInventoryItemId the donor's Lager row to earmark, or {@code null}
 * @param anonymous whether the donor stays anonymous to the receiving unit
 */
@DtoMirror
public record PledgeRequest(
    UUID needId,
    UUID receivingOrgUnitId,
    UUID materialId,
    UUID gameItemId,
    Integer quality,
    @NotNull @Positive Double amount,
    UUID sourceInventoryItemId,
    boolean anonymous) {}
