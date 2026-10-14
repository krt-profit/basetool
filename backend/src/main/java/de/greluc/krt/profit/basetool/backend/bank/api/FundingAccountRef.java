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

package de.greluc.krt.profit.basetool.backend.bank.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * What another module may know of an account it pays from (REQ-BANK-061): its identity and kind, no
 * balance and no owner.
 *
 * @param id the account
 * @param accountNo the human-readable account number
 * @param name the display name
 * @param type the account type name, {@code CARTEL} or {@code SPECIAL}
 */
public record FundingAccountRef(
    @NotNull UUID id, @NotNull String accountNo, @NotNull String name, @NotNull String type) {}
