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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.ExternalSyncReport;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link ExternalSyncReport}. Read access is paged + most-recent-first
 * for the admin pages; the retention sweep ({@link #findRecentRunIds} + {@link
 * #deleteBySourceAndRunIdNotIn}) keeps the table bounded at the last N runs per source.
 */
@Repository
public interface ExternalSyncReportRepository extends JpaRepository<ExternalSyncReport, UUID> {

  /**
   * Paged, most-recent-first view of every event, regardless of source. Backs the combined {@code
   * /admin/sync-reports} page.
   *
   * @param pageable paging + (ignored) sort — ordering is fixed to {@code ran_at DESC}
   * @return one page of events newest-first
   */
  Page<ExternalSyncReport> findAllByOrderByRanAtDesc(Pageable pageable);

  /**
   * Pages one source's sync events, newest first.
   *
   * @param sourceSystem the catalogue to filter to
   * @param pageable paging
   * @return one page of that source's events newest-first
   */
  Page<ExternalSyncReport> findBySourceSystemOrderByRanAtDesc(
      SyncSourceSystem sourceSystem, Pageable pageable);

  /**
   * Returns the {@code run_id}s of a source's most recent runs, newest first, for the retention
   * sweep's keep set.
   *
   * @param sourceSystem the catalogue to scope to
   * @param pageable a {@code PageRequest.of(0, N)} limiting the result to the newest N runs
   * @return run ids newest-first
   */
  @Query(
      """
      SELECT r.runId FROM ExternalSyncReport r WHERE r.sourceSystem = :source
      GROUP BY r.runId ORDER BY MAX(r.ranAt) DESC
      """)
  List<UUID> findRecentRunIds(@Param("source") SyncSourceSystem sourceSystem, Pageable pageable);

  /**
   * Deletes every event of a source whose {@code run_id} is not in the keep set. Callers must pass
   * a non-empty keep set.
   *
   * @param sourceSystem the catalogue to scope the delete to
   * @param keptRunIds run ids to preserve (the newest N)
   * @return number of rows deleted
   */
  @Modifying
  @Query(
      """
      DELETE FROM ExternalSyncReport r WHERE r.sourceSystem = :source
      AND r.runId NOT IN :keptRunIds
      """)
  int deleteBySourceAndRunIdNotIn(
      @Param("source") SyncSourceSystem sourceSystem,
      @Param("keptRunIds") Collection<UUID> keptRunIds);

  /**
   * Deletes every event of any source whose {@code ran_at} predates {@code cutoff}.
   *
   * @param cutoff the exclusive upper bound; rows strictly older than this are removed
   * @return number of rows deleted
   */
  @Modifying
  @Query("DELETE FROM ExternalSyncReport r WHERE r.ranAt < :cutoff")
  int deleteByRanAtBefore(@Param("cutoff") Instant cutoff);

  /**
   * Deletes every event of one source whose {@code ran_at} predates {@code cutoff}.
   *
   * @param sourceSystem the catalogue to scope the delete to
   * @param cutoff the exclusive upper bound; rows strictly older than this are removed
   * @return number of rows deleted
   */
  @Modifying
  @Query("DELETE FROM ExternalSyncReport r WHERE r.sourceSystem = :source AND r.ranAt < :cutoff")
  int deleteBySourceSystemAndRanAtBefore(
      @Param("source") SyncSourceSystem sourceSystem, @Param("cutoff") Instant cutoff);
}
