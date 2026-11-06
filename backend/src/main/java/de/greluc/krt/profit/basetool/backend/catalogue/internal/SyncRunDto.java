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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Lifecycle and counters of an external catalogue attempt.
 *
 * @param runId durable attempt identity
 * @param aggregate independently applied catalogue slice
 * @param status application outcome
 * @param startedAt actual attempt start
 * @param finishedAt actual finish, absent while running or after an interrupted process
 * @param gameVersion frozen upstream build when available
 * @param fetched decoded list rows across source requests
 * @param valid decoded rows with usable source identities
 * @param applied committed row writes
 * @param skipped source rows excluded by validation or matching
 * @param failed fetch or write failures
 * @param censusComplete whether every fetched list vouched for its census
 * @param sweepOutcome whether absence-based cleanup ran
 */
public record SyncRunDto(
    UUID runId,
    String aggregate,
    String status,
    Instant startedAt,
    @Nullable Instant finishedAt,
    @Nullable String gameVersion,
    long fetched,
    long valid,
    long applied,
    long skipped,
    long failed,
    boolean censusComplete,
    String sweepOutcome) {

  /**
   * Copies a persisted attempt into its read-only representation.
   *
   * @param run persisted attempt
   * @return lifecycle and counters without managed entity references
   */
  public static SyncRunDto from(ExternalSyncRun run) {
    return new SyncRunDto(
        run.getId(),
        run.getAggregate().name(),
        run.getStatus().name(),
        run.getStartedAt(),
        run.getFinishedAt(),
        run.getGameVersion(),
        run.getFetched(),
        run.getValid(),
        run.getApplied(),
        run.getSkipped(),
        run.getFailed(),
        run.isCensusComplete(),
        run.getSweepOutcome().name());
  }
}
