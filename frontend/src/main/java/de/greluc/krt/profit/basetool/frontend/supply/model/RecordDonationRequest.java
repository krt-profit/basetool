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
 * Frontend mirror of the backend {@code RecordDonationRequest}; must match it field for field.
 *
 * @param receivingOrgUnitId the receiving unit
 * @param needId the need to credit, or {@code null}
 * @param materialId the material, or {@code null}
 * @param gameItemId the game item, or {@code null}
 * @param quality the quality of a material, or {@code null}
 * @param amount the received amount
 * @param donorUserId the donor, or {@code null}
 * @param anonymous whether the donor stays anonymous to the receiving unit
 * @param locationId where the stock lies now
 */
@DtoMirror
public record RecordDonationRequest(
    @NotNull UUID receivingOrgUnitId,
    UUID needId,
    UUID materialId,
    UUID gameItemId,
    Integer quality,
    @NotNull @Positive Double amount,
    UUID donorUserId,
    boolean anonymous,
    @NotNull UUID locationId) {}
