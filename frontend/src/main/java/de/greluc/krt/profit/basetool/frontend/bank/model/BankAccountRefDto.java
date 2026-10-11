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

package de.greluc.krt.profit.basetool.frontend.bank.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of a lightweight bank-account reference (REQ-BANK-040) used to populate the
 * transfer-request destination picker; the account type arrives as an enum name.
 *
 * @param id the account id (echoed back as the transfer destination)
 * @param accountNo the human-readable {@code KB-<n>} number
 * @param name the account's display name
 * @param type account-type enum name
 */
@DtoMirror
public record BankAccountRefDto(
    UUID id, String accountNo, String name, @BackendEnumAsString String type) {}
