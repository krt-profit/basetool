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

import de.greluc.krt.profit.basetool.backend.validation.WholeNumber;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for a holder→holder Umbuchung (REQ-BANK-031, ADR-0039): two holder legs summing to
 * zero and no account leg — pure custody reconciliation between two players. The amount is
 * whole-aUEC and strictly positive; the source holder may end up negative (no holder overdraft,
 * REQ-BANK-006). Source and destination holder must differ ({@code BANK_SELF_TRANSFER} otherwise).
 *
 * @param sourceHolderId the player whose global stash shrinks
 * @param destinationHolderId the player whose global stash grows
 * @param amount whole-aUEC amount, at least 1
 * @param note optional free-text note for the audit trail
 */
public record BankHolderTransferRequest(
    @NotNull UUID sourceHolderId,
    @NotNull UUID destinationHolderId,
    @NotNull @DecimalMin("1") @DecimalMax("1000000000000.0") @WholeNumber BigDecimal amount,
    @Nullable @Size(max = 500) String note) {}
