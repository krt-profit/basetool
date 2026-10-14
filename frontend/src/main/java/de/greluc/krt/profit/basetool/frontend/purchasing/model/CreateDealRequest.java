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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code CreateDealRequest}; must match it field for field.
 *
 * @param needId the need
 * @param partnerId the counterparty
 * @param amount the amount to buy
 * @param unitPrice the unit price in whole aUEC
 * @param paymentModel how the counterparty is paid
 * @param riskClass a class to raise to, or {@code null}
 * @param escortWish whether an escort is wished
 * @param missionId the escorting Einsatz, or {@code null}
 */
@DtoMirror
public record CreateDealRequest(
    @NotNull UUID needId,
    @NotNull UUID partnerId,
    @NotNull @Positive Double amount,
    @NotNull @Positive Long unitPrice,
    @NotNull @BackendEnumAsString String paymentModel,
    @BackendEnumAsString String riskClass,
    boolean escortWish,
    UUID missionId) {}
