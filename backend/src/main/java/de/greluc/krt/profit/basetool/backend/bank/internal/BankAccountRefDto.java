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

import java.util.UUID;

/**
 * A lightweight reference to a bank account (id + display number + name + type) — used to populate
 * the transfer-request destination picker (REQ-BANK-040), where a requester may choose any active
 * account as the target. Carries no balance, history or holders.
 *
 * @param id the account id (echoed back as the transfer destination)
 * @param accountNo the human-readable {@code KB-<n>} account number
 * @param name the account's display name
 * @param type the account type, for labelling the option
 */
public record BankAccountRefDto(UUID id, String accountNo, String name, BankAccountType type) {}
