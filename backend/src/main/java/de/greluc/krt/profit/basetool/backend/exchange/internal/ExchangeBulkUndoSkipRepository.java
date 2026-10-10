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

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for the entries a bulk undo left alone (REQ-XCH-034). */
@Repository
public interface ExchangeBulkUndoSkipRepository extends JpaRepository<ExchangeBulkUndoSkip, UUID> {

  /**
   * Lists one page of a run's skipped entries, failed members first.
   *
   * @param runId the run
   * @param page the page
   * @return the skipped entries
   */
  @Query(
      """
      SELECT s FROM ExchangeBulkUndoSkip s WHERE s.runId = :runId
      ORDER BY CASE WHEN s.reason = 'FAILED' THEN 0 ELSE 1 END, s.userId, s.id
      """)
  List<ExchangeBulkUndoSkip> findPage(@Param("runId") UUID runId, Pageable page);

  /**
   * Counts a run's skipped entries.
   *
   * @param runId the run
   * @return how many there are
   */
  long countByRunId(UUID runId);
}
