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

import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Edits a draft deal (REQ-PUR-007). The need is fixed. PUT semantics: an omitted optional field
 * clears it.
 *
 * @param partnerId the counterparty
 * @param amount the amount to buy
 * @param unitPrice the price of one unit in whole aUEC
 * @param paymentModel how the counterparty is paid
 * @param riskClass a class to raise the computed one to, or {@code null}
 * @param escortWish whether an escort is wished
 * @param missionId the escorting Einsatz, or {@code null}
 * @param version the echoed version of the deal header
 */
public record UpdateDealRequest(
    @NotNull UUID partnerId,
    @NotNull @Positive Double amount,
    @NotNull @Positive Long unitPrice,
    @NotNull PaymentModel paymentModel,
    @Nullable RiskClassCode riskClass,
    boolean escortWish,
    @Nullable UUID missionId,
    @NotNull Long version) {}
