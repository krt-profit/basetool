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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Normalized Bank booking terms for direct execution and later confirmation.
 *
 * @param type the Bank effect
 * @param account the principal account, absent for a holder transfer
 * @param target the destination account for an account transfer
 * @param holder the registered source or receiving custodian
 * @param targetHolder the second custodian for a transfer
 * @param counterparty the actual depositor or payee
 * @param amount principal in whole aUEC
 * @param note the applicant's booking note
 * @param justification the debit justification
 */
record BankLinkedBooking(
    @NotNull BankBookingRequestType type,
    @Nullable BankAccount account,
    @Nullable BankAccount target,
    @NotNull BankHolder holder,
    @Nullable BankHolder targetHolder,
    @Nullable CounterpartySnapshot counterparty,
    @NotNull BigDecimal amount,
    @Nullable String note,
    @Nullable String justification) {}
