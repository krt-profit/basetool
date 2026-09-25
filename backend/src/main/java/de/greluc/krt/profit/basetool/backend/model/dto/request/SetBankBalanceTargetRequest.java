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
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for bank staff setting or clearing a bank account's balance target
 * ("Kontostandsziel", REQ-BANK-036). A {@code null} target clears it; otherwise it is a whole-aUEC
 * amount of at least 1. The org-unit-facing endpoint uses {@code OrgUnitBalanceTargetRequest}.
 *
 * @param target the new balance target, or {@code null} to clear it
 * @param version optimistic-locking version the client read (REQ-BANK-018); a mismatch surfaces as
 *     409 {@code OPTIMISTIC_LOCK}
 */
public record SetBankBalanceTargetRequest(
    @Nullable @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal target,
    @NotNull Long version) {}
