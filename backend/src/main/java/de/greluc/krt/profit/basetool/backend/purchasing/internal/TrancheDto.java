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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One money tranche of a deal (REQ-PUR-012, REQ-PUR-014).
 *
 * @param id the tranche
 * @param kind what the tranche pays
 * @param amount the requested amount in whole aUEC
 * @param state where the tranche stands in the bank
 * @param bankRequestId the bank request that pays it
 * @param decidedAt when the bank decided, or {@code null} while pending
 * @param createdAt when the tranche was requested
 * @param version the optimistic-locking version to echo
 */
public record TrancheDto(
    UUID id,
    TrancheKind kind,
    long amount,
    TrancheState state,
    @Nullable UUID bankRequestId,
    @Nullable Instant decidedAt,
    Instant createdAt,
    Long version) {}
