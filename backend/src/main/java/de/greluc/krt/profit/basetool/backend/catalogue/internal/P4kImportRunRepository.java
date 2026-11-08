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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for {@link P4kImportRun}. */
public interface P4kImportRunRepository extends JpaRepository<P4kImportRun, UUID> {

  /**
   * Returns the highest change number any applied run carries; within the LIVE Channel that is the
   * change number of the last applied run (REQ-P4K-004).
   *
   * @return the highest change number, empty before the first run
   */
  @Query("SELECT MAX(r.changeNumber) FROM P4kImportRun r")
  Optional<Long> findHighestChangeNumber();

  /**
   * Returns the run applied last.
   *
   * @return the latest run, empty before the first run
   */
  Optional<P4kImportRun> findFirstByOrderByAppliedAtDesc();

  /**
   * Returns the run applied last among those whose patch version is known, the comparison basis of
   * "P4K import due" (REQ-OBS-022).
   *
   * @return the latest run with a patch version, empty when no run has one
   */
  Optional<P4kImportRun> findFirstByPatchVersionIsNotNullOrderByAppliedAtDesc();

  /**
   * Returns the last two applied runs, newest first, for the last-run gauges and the {@code
   * itemClass} null share's change (REQ-OBS-022).
   *
   * @return at most two runs, newest first
   */
  List<P4kImportRun> findTop2ByOrderByAppliedAtDesc();

  /**
   * Pages the applied runs, newest first (REQ-P4K-015).
   *
   * @param pageable the page
   * @return the page of runs
   */
  Page<P4kImportRun> findAllByOrderByAppliedAtDesc(Pageable pageable);
}
