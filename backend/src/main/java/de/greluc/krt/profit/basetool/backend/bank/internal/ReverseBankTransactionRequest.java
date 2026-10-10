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

import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload for reversing a transaction (REQ-BANK-004): the correction books a {@code REVERSAL}
 * whose legs are the negated mirror of the original's legs (ADR-0010). The target transaction
 * travels in the URL; the body only carries the optional correction note.
 *
 * @param note optional reason shown in the booking history (e.g. "Tippfehler korrigiert")
 */
public record ReverseBankTransactionRequest(@Nullable @Size(max = 500) String note) {}
