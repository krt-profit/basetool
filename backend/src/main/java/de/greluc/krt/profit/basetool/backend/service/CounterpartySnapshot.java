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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of a deposit/withdrawal counterparty (REQ-BANK-044, #994) threaded from {@link
 * BankLedgerService}'s counterparty resolution onto the transaction header stamped by {@link
 * BankPostingWriter#persistTransaction} — the far-side party and, optionally, the org unit they
 * belong to, each captured with a deletion-proof name snapshot.
 *
 * @param userId the registered counterparty user id, or {@code null} for an external free-text
 *     counterparty (#994) — the handle then carries the entered name and no FK is stored
 * @param handle the party's name snapshot (a registered user's effective name, or the external
 *     free-text name)
 * @param orgUnitId the chosen org unit id, or {@code null}
 * @param orgUnitName the org unit's name snapshot, or {@code null} when no org unit was chosen
 */
public record CounterpartySnapshot(
    @Nullable UUID userId,
    @NotNull String handle,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName) {}
