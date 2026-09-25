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

import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for creating a bank account (REQ-BANK-001/-002). The type-specific owner rules are
 * validated in {@code BankAccountService}.
 *
 * @param name display name of the new account
 * @param type the organizational layer; immutable after creation
 * @param orgUnitId owning org unit — required for {@code ORG_UNIT} (Staffel/SK) and {@code AREA}
 *     (Bereich), optional for {@code CARTEL} (the OL), forbidden for {@code CARTEL_BANK}/{@code
 *     SPECIAL}
 * @param areaName free-form Bereich name for an {@code AREA} account without the Bereich FK;
 *     forbidden for every other type and for FK-linked AREA accounts
 */
public record CreateBankAccountRequest(
    @NotBlank @Size(max = 255) String name,
    @NotNull BankAccountType type,
    @Nullable UUID orgUnitId,
    @Nullable @Size(max = 255) String areaName) {}
