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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code TrancheDto}; must match it field for field.
 *
 * @param id the tranche
 * @param kind what the tranche pays
 * @param amount the requested amount in whole aUEC
 * @param state where the tranche stands in the bank
 * @param bankRequestId the bank request that pays it
 * @param decidedAt when the bank decided, or {@code null}
 * @param createdAt when the tranche was requested
 * @param version the optimistic-locking version
 */
@DtoMirror
public record TrancheDto(
    UUID id,
    @BackendEnumAsString String kind,
    long amount,
    @BackendEnumAsString String state,
    UUID bankRequestId,
    Instant decidedAt,
    Instant createdAt,
    Long version) {}
