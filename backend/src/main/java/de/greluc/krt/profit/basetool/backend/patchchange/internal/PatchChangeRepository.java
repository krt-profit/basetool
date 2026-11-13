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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository of the patch changes (REQ-PATCH-001). */
@Repository
public interface PatchChangeRepository extends JpaRepository<PatchChange, UUID> {

  /** The advisory-lock space of the patch-change history, {@code PTCH} in ASCII. */
  int HISTORY_LOCK_SPACE = 0x50544348;

  /**
   * Takes the transaction-scoped lock that serialises every write of the history, so two triggers
   * or a trigger and a label correction never judge the order of labels and live times on a stale
   * read.
   *
   * @return always {@code 1}
   */
  @Query(
      value = "SELECT 1 FROM pg_advisory_xact_lock(" + HISTORY_LOCK_SPACE + ", 0)",
      nativeQuery = true)
  int lockHistory();

  /**
   * Returns the latest patch change by live time.
   *
   * @return the latest one, or empty before the first
   */
  Optional<PatchChange> findFirstByOrderByLiveAtDesc();

  /**
   * Returns the latest live time of every patch change, the cutoff of the write-time stock merge
   * (REQ-PATCH-004).
   *
   * @return the latest live time, or empty before the first patch change
   */
  @Query("SELECT MAX(p.liveAt) FROM PatchChange p")
  Optional<Instant> findLatestLiveAt();

  /**
   * Returns the patch change live before a given one.
   *
   * @param liveAt the given one's live time
   * @return the latest patch change live before it, or empty for the first
   */
  Optional<PatchChange> findFirstByLiveAtLessThanOrderByLiveAtDesc(Instant liveAt);

  /**
   * Returns the patch change live after a given one.
   *
   * @param liveAt the given one's live time
   * @return the earliest patch change live after it, or empty for the latest
   */
  Optional<PatchChange> findFirstByLiveAtGreaterThanOrderByLiveAtAsc(Instant liveAt);

  /**
   * Tells whether another patch change carries a label.
   *
   * @param label the label
   * @param id the patch change to leave out
   * @return {@code true} when a different patch change has that label
   */
  boolean existsByLabelAndIdNot(String label, UUID id);

  /**
   * Lists every patch change with its wipe groups and counts, newest live time first, for the admin
   * history (REQ-PATCH-008).
   *
   * @return the history
   */
  @EntityGraph(attributePaths = {"wipeGroups", "effectCounts"})
  List<PatchChange> findAllByOrderByLiveAtDesc();

  /**
   * Loads one patch change with its wipe groups and counts.
   *
   * @param id the patch change
   * @return the patch change, or empty when none has that id
   */
  @EntityGraph(attributePaths = {"wipeGroups", "effectCounts"})
  Optional<PatchChange> findWithDetailsById(UUID id);

  /**
   * Reads the labels of the given patch changes.
   *
   * @param ids the patch changes
   * @return one id and label pair per patch change found
   */
  @Query("SELECT p.id AS id, p.label AS label FROM PatchChange p WHERE p.id IN :ids")
  List<LabelRow> findLabels(@Param("ids") Collection<UUID> ids);

  /** One patch change's label as a projection returns it. */
  interface LabelRow {

    /**
     * The patch change's id.
     *
     * @return the id
     */
    UUID getId();

    /**
     * The patch change's label.
     *
     * @return the label
     */
    String getLabel();
  }
}
