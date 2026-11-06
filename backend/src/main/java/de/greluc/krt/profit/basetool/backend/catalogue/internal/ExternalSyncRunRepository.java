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
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Persists complete catalogue attempts independently of optional anomaly events. */
public interface ExternalSyncRunRepository extends JpaRepository<ExternalSyncRun, UUID> {

  /**
   * Reads the most recent attempts of one source, including unfinished attempts.
   *
   * @param sourceSystem the external source
   * @param pageable the requested bounded history page
   * @return attempts ordered by actual start time
   */
  List<ExternalSyncRun> findBySourceSystemOrderByStartedAtDesc(
      SyncSourceSystem sourceSystem, Pageable pageable);

  /**
   * Counts attempts started inside the requested range.
   *
   * @param sourceSystem the external source
   * @param aggregate independently applied slice or complete source sweep
   * @param since inclusive start time
   * @return number of attempts, regardless of their outcome
   */
  long countBySourceSystemAndAggregateAndStartedAtGreaterThanEqual(
      SyncSourceSystem sourceSystem, SyncAggregate aggregate, Instant since);

  /**
   * Reads the latest successfully applied census of one source.
   *
   * @param sourceSystem the external source
   * @param aggregate independently applied slice or complete source sweep
   * @param statuses complete application outcomes
   * @param pageable bounds the result to the desired number of attempts
   * @return successful attempts in actual finish order
   */
  List<ExternalSyncRun> findBySourceSystemAndAggregateAndStatusInOrderByFinishedAtDesc(
      SyncSourceSystem sourceSystem,
      SyncAggregate aggregate,
      List<SyncRunStatus> statuses,
      Pageable pageable);

  /**
   * Reads one latest attempt per bounded source and slice for startup metric restoration.
   *
   * @return latest attempts ordered by source and slice
   */
  @Query(
      value =
          "SELECT DISTINCT ON (source_system, aggregate) * FROM external_sync_run "
              + "ORDER BY source_system, aggregate, started_at DESC, id DESC",
      nativeQuery = true)
  List<ExternalSyncRun> findLatestAttempts();

  /**
   * Reads each source slice's latest complete application for startup freshness restoration.
   *
   * @return complete attempts ordered by source and slice
   */
  @Query(
      value =
          "SELECT DISTINCT ON (source_system, aggregate) * FROM external_sync_run "
              + "WHERE status IN ('SUCCESS', 'UNCHANGED') AND finished_at IS NOT NULL "
              + "ORDER BY source_system, aggregate, finished_at DESC, id DESC",
      nativeQuery = true)
  List<ExternalSyncRun> findLatestCompleteAttempts();

  /**
   * Marks attempts interrupted by process termination failed without inventing a finish time.
   *
   * @param running persisted active status
   * @param failed persisted terminal failure status
   * @return interrupted attempts recovered at process startup
   */
  @Modifying
  @Query(
      "UPDATE ExternalSyncRun r SET r.status = :failed, r.failed = r.failed + 1, r.version ="
          + " r.version + 1 WHERE r.status = :running")
  int failInterruptedAttempts(SyncRunStatus running, SyncRunStatus failed);
}
