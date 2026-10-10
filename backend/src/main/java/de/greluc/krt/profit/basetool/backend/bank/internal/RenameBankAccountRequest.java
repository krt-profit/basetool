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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Write payload for renaming a bank account. Only the display name is mutable — number, type and
 * owner reference are fixed at creation (REQ-BANK-001).
 *
 * @param name the new display name
 * @param version optimistic-locking version the client read (REQ-BANK-018); a mismatch surfaces as
 *     409 {@code OPTIMISTIC_LOCK}
 */
public record RenameBankAccountRequest(
    @NotBlank @Size(max = 255) String name, @NotNull Long version) {}
