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
 * Immutable snapshot of a deposit or withdrawal counterparty and optionally their org unit, each
 * with a deletion-proof name snapshot (REQ-BANK-044).
 *
 * @param userId the registered counterparty user id, or {@code null} for an external free-text
 *     counterparty
 * @param handle the party's name snapshot
 * @param orgUnitId the chosen org unit id, or {@code null}
 * @param orgUnitName the org unit's name snapshot, or {@code null} when none was chosen
 */
public record CounterpartySnapshot(
    @Nullable UUID userId,
    @NotNull String handle,
    @Nullable UUID orgUnitId,
    @Nullable String orgUnitName) {}
