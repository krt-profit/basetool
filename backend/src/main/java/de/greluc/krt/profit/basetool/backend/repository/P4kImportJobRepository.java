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

import de.greluc.krt.profit.basetool.backend.model.P4kImportJob;
import de.greluc.krt.profit.basetool.backend.model.P4kImportJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link P4kImportJob} async import-run rows. */
public interface P4kImportJobRepository extends JpaRepository<P4kImportJob, UUID> {

  /**
   * Counts import jobs in the given status, backing the {@code basetool_p4k_import_job_pending_*}
   * queue-depth gauge (REQ-OBS-011).
   *
   * @param status the bounded job status to count
   * @return the number of import jobs in that status
   */
  long countByStatus(P4kImportJobStatus status);

  /**
   * Finds the creation timestamp of the oldest import job in the given status, for the "oldest
   * un-picked-up import job age" gauge (REQ-OBS-011).
   *
   * @param status the bounded job status to scan (typically {@code PENDING})
   * @return the earliest {@code createdAt} in that status, or {@code null} when none exists
   */
  @Query("SELECT MIN(j.createdAt) FROM P4kImportJob j WHERE j.status = :status")
  Instant findOldestCreatedAtByStatus(@Param("status") P4kImportJobStatus status);

  /**
   * Returns the most recent import jobs, newest first, capped for the admin job list (the table is
   * kept small by the prune sweep, so a fixed window is sufficient and avoids unbounded growth).
   *
   * @return up to 50 jobs ordered by creation time descending
   */
  List<P4kImportJob> findTop50ByOrderByCreatedAtDesc();

  /**
   * Returns the jobs currently in one of the given statuses. Used on startup to reconcile jobs left
   * {@code PENDING} / {@code RUNNING} by a backend restart: the import worker does not survive a
   * restart, so such jobs can never make progress and are flipped to {@code FAILED}.
   *
   * @param statuses the statuses to match
   * @return the matching jobs (unordered)
   */
  List<P4kImportJob> findByStatusIn(List<P4kImportJobStatus> statuses);

  /**
   * Bulk-deletes jobs created before {@code cutoff}. Their {@link
   * de.greluc.krt.profit.basetool.backend.model.P4kImportJobPayload} rows are removed by the {@code
   * ON DELETE CASCADE} foreign key at the database layer.
   *
   * @param cutoff jobs created strictly before this instant are deleted
   * @return the number of job rows deleted
   */
  @Modifying
  @Query("delete from P4kImportJob j where j.createdAt < :cutoff")
  int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
