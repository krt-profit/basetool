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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of one durable catalogue attempt.
 *
 * @param runId durable attempt identity
 * @param aggregate independently applied catalogue slice
 * @param status application outcome
 * @param startedAt actual start
 * @param finishedAt actual finish, absent while running or after interruption
 * @param gameVersion frozen upstream build when available
 * @param fetched decoded source rows
 * @param valid source rows with usable identities
 * @param applied committed row writes
 * @param skipped deliberately rejected rows
 * @param failed failed fetch or write operations
 * @param censusComplete whether the source scope had a verified complete census
 * @param sweepOutcome absence-based cleanup decision
 */
@DtoMirror
public record SyncRunDto(
    UUID runId,
    String aggregate,
    String status,
    Instant startedAt,
    Instant finishedAt,
    String gameVersion,
    long fetched,
    long valid,
    long applied,
    long skipped,
    long failed,
    boolean censusComplete,
    String sweepOutcome) {}
