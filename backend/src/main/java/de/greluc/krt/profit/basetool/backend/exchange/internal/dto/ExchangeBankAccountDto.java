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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One bank account the member views, in the shape of the published {@code bank-account.schema.json}
 * (REQ-BXCH-002): no holders, custody, limit tables, ceilings or version.
 *
 * @param accountId the account's id, opaque to the client
 * @param accountNo the {@code KB-<n>} account number
 * @param name the display name
 * @param type the account type, such as {@code ORG_UNIT} or {@code CARTEL}
 * @param orgUnit the owning org unit, or {@code null} for an account without one
 * @param balance the current balance in whole aUEC
 * @param balanceTarget the balance goal in whole aUEC, or {@code null}
 * @param delta30d the net change over the last 30 days in whole aUEC
 * @param sparkline the 30 end-of-day balances, oldest first, ending with the current balance
 * @param requestable whether the member may raise a withdrawal or transfer here
 * @param ownLimit the member's applicable limit above which a request needs an approval, or {@code
 *     null}
 */
public record ExchangeBankAccountDto(
    @NotNull UUID accountId,
    @NotNull String accountNo,
    @NotNull String name,
    @NotNull String type,
    @Nullable ExchangeOrgUnitDto orgUnit,
    long balance,
    @Nullable Long balanceTarget,
    long delta30d,
    @NotNull @Unmodifiable List<Long> sparkline,
    boolean requestable,
    @Nullable Long ownLimit) {}
