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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link P4kImportChange}. */
public interface P4kImportChangeRepository extends JpaRepository<P4kImportChange, UUID> {

  /**
   * Counts a run's report rows of one kind, change type and field (REQ-OBS-022).
   *
   * @param runId the run
   * @param kind the kind
   * @param changeType the change type
   * @param fieldName the field name
   * @return the number of rows
   */
  long countByRunIdAndKindAndChangeTypeAndFieldName(
      UUID runId, P4kKind kind, P4kChangeType changeType, String fieldName);

  /**
   * Lists the report rows of one run.
   *
   * @param runId the run
   * @return the run's report rows, in no particular order
   */
  List<P4kImportChange> findByRunId(UUID runId);

  /**
   * Lists the report rows of one change type among some runs and GUIDs, for the review list's
   * record names.
   *
   * @param changeType the change type
   * @param runIds the runs
   * @param guids the GUIDs
   * @return the matching rows, in no particular order
   */
  List<P4kImportChange> findByChangeTypeAndRunIdInAndGuidIn(
      P4kChangeType changeType, Collection<UUID> runIds, Collection<UUID> guids);

  /**
   * Pages the report rows of one run in the order the run found them, optionally filtered by kind
   * and change type (REQ-P4K-015).
   *
   * @param runId the run
   * @param kind the kind to show, or {@code null} for all
   * @param changeType the change type to show, or {@code null} for all
   * @param pageable the page, its sort ignored
   * @return the page of rows
   */
  @Query(
      """
      SELECT c FROM P4kImportChange c WHERE c.runId = :runId
      AND (:kind IS NULL OR c.kind = :kind)
      AND (:changeType IS NULL OR c.changeType = :changeType)
      ORDER BY c.position ASC
      """)
  Page<P4kImportChange> findPage(
      @Param("runId") UUID runId,
      @Param("kind") @Nullable P4kKind kind,
      @Param("changeType") @Nullable P4kChangeType changeType,
      Pageable pageable);
}
