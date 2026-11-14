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
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
   * Takes the history's lock in shared mode, so relocations run side by side but never beside a
   * trigger or a phase end, which take it exclusively (REQ-PATCH-026).
   *
   * @return always {@code 1}
   */
  @Query(
      value = "SELECT 1 FROM pg_advisory_xact_lock_shared(" + HISTORY_LOCK_SPACE + ", 0)",
      nativeQuery = true)
  int lockHistoryShared();

  /**
   * Returns the latest patch change that asked for a new home location, the only one whose phase
   * can be open (REQ-PATCH-024).
   *
   * @return the latest one with the prompt on, or empty when none asked
   */
  Optional<PatchChange> findFirstByHomeLocationPromptTrueOrderByLiveAtDesc();

  /**
   * Ends a home-location phase, leaving the label and its version untouched.
   *
   * @param id the patch change
   * @param endedAt when the phase ended
   * @param endedBy the admin who ended it, or {@code null} when no member is known
   * @return {@code 1} when the phase ended now, {@code 0} when it had ended before
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE patch_change SET phase_ended_at = :endedAt, phase_ended_by = :endedBy"
              + " WHERE id = :id AND home_location_prompt AND phase_ended_at IS NULL",
      nativeQuery = true)
  int endPhase(
      @Param("id") UUID id,
      @Param("endedAt") Instant endedAt,
      @Param("endedBy") @Nullable UUID endedBy);

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
