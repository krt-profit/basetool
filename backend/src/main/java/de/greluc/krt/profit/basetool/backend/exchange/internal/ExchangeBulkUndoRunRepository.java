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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for the admins' bulk undo runs (REQ-XCH-034). */
@Repository
public interface ExchangeBulkUndoRunRepository extends JpaRepository<ExchangeBulkUndoRun, UUID> {

  /**
   * Whether a client has a run in a state.
   *
   * @param exchangeClientId the registry id of the client
   * @param status the state
   * @return {@code true} when such a run exists
   */
  boolean existsByExchangeClientIdAndStatus(UUID exchangeClientId, ExchangeBulkUndoStatus status);

  /**
   * Lists the most recent runs, newest first.
   *
   * @return at most twenty runs
   */
  List<ExchangeBulkUndoRun> findTop20ByOrderByStartedAtDesc();

  /**
   * Lists the runs in a state.
   *
   * @param status the state
   * @return the runs
   */
  List<ExchangeBulkUndoRun> findAllByStatus(ExchangeBulkUndoStatus status);

  /**
   * Adds one processed member's outcome to a running run in one atomic statement.
   *
   * @param id the run
   * @param restored the entries restored for the member
   * @param skipped the entries left alone for the member
   * @param failed {@code 1} when the member could not be processed, else {@code 0}
   * @return the number of rows changed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      """
      UPDATE ExchangeBulkUndoRun r
      SET r.membersDone = r.membersDone + 1, r.membersFailed = r.membersFailed + :failed,
          r.restored = r.restored + :restored, r.skipped = r.skipped + :skipped
      WHERE r.id = :id AND r.status = de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBulkUndoStatus.RUNNING
      """)
  int addProgress(
      @Param("id") UUID id,
      @Param("restored") int restored,
      @Param("skipped") int skipped,
      @Param("failed") int failed);

  /**
   * Ends a running run.
   *
   * @param id the run
   * @param status {@code COMPLETED} or {@code FAILED}
   * @param finishedAt the end time
   * @return the number of rows changed; {@code 0} when it was no longer running
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE ExchangeBulkUndoRun r SET r.status = :status, r.finishedAt = :finishedAt
      WHERE r.id = :id AND r.status = de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeBulkUndoStatus.RUNNING
      """)
  int finish(
      @Param("id") UUID id,
      @Param("status") ExchangeBulkUndoStatus status,
      @Param("finishedAt") Instant finishedAt);

  /**
   * Deletes the runs that ended before a cutoff, with their skipped entries.
   *
   * @param cutoff the oldest end still kept
   * @return the number of runs deleted
   */
  @Modifying
  @Query(
      value = "DELETE FROM exchange_bulk_undo_run WHERE finished_at < :cutoff",
      nativeQuery = true)
  int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
