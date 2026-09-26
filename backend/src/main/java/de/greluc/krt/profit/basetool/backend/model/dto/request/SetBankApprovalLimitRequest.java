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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import de.greluc.krt.profit.basetool.backend.validation.WholeNumber;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Write payload for upserting one per-tier approval limit on a bank account (REQ-BANK-041); the
 * tier is named by the URL path. A limit of {@code 0} means every request of that tier needs
 * approval; a limit is removed with DELETE.
 *
 * @param limit the whole-aUEC ceiling, at least 0, up to which the tier may request without
 *     approval
 */
public record SetBankApprovalLimitRequest(
    @NotNull @DecimalMin("0") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal limit) {}
