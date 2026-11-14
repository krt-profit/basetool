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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The home-location choices and the members a phase asks (REQ-PATCH-021): an active member whose
 * account was created before the phase's live time, or has no creation time, is asked.
 */
@Repository
public interface PatchChangeRelocationRepository
    extends JpaRepository<PatchChangeRelocation, PatchChangeRelocation.Key> {

  /**
   * Claims the member's one relocation in a phase before anything moves; a concurrent second claim
   * waits for the first and then finds the key taken.
   *
   * @param patchChangeId the phase's patch change
   * @param userId the member
   * @param homeLocationId the chosen home location
   * @param chosenBy who chose, as the enum's name
   * @param relocatedAt when the relocation runs
   * @return {@code 1} when the claim is new, {@code 0} when the member had chosen before
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO patch_change_relocation (patch_change_id, user_id, home_location_id,
              chosen_by, relocated_at, rows_moved, ships_moved)
          VALUES (:patchChangeId, :userId, :homeLocationId, :chosenBy, :relocatedAt, 0, 0)
          ON CONFLICT (patch_change_id, user_id) DO NOTHING
          """,
      nativeQuery = true)
  int claim(
      @Param("patchChangeId") UUID patchChangeId,
      @Param("userId") UUID userId,
      @Param("homeLocationId") UUID homeLocationId,
      @Param("chosenBy") String chosenBy,
      @Param("relocatedAt") Instant relocatedAt);

  /**
   * Records what a claimed relocation moved.
   *
   * @param patchChangeId the phase's patch change
   * @param userId the member
   * @param rows how many stock rows moved
   * @param ships how many ships moved
   * @return {@code 1}
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE patch_change_relocation SET rows_moved = :rows, ships_moved = :ships
          WHERE patch_change_id = :patchChangeId AND user_id = :userId
          """,
      nativeQuery = true)
  int recordMoved(
      @Param("patchChangeId") UUID patchChangeId,
      @Param("userId") UUID userId,
      @Param("rows") int rows,
      @Param("ships") int ships);

  /**
   * Returns the members among the given ones a phase asks who have not chosen in it.
   *
   * @param users the members to look at
   * @param patchChangeId the phase's patch change
   * @param liveAt the phase's live time
   * @param active the approval status of an active member
   * @return the pending members
   */
  @Query(
      """
      SELECT u.id FROM User u
      WHERE u.id IN :users AND u.approvalStatus = :active
        AND (u.createdAt IS NULL OR u.createdAt < :liveAt)
        AND NOT EXISTS (
          SELECT r.userId FROM PatchChangeRelocation r
          WHERE r.patchChangeId = :patchChangeId AND r.userId = u.id)
      """)
  List<UUID> findPending(
      @Param("users") Collection<UUID> users,
      @Param("patchChangeId") UUID patchChangeId,
      @Param("liveAt") Instant liveAt,
      @Param("active") ApprovalStatus active);

  /**
   * Counts the members a phase asks.
   *
   * @param liveAt the phase's live time
   * @param active the approval status of an active member
   * @return how many members are asked
   */
  @Query(
      """
      SELECT COUNT(u) FROM User u
      WHERE u.approvalStatus = :active AND (u.createdAt IS NULL OR u.createdAt < :liveAt)
      """)
  long countAsked(@Param("liveAt") Instant liveAt, @Param("active") ApprovalStatus active);

  /**
   * Counts the members a phase asks who have chosen in it.
   *
   * @param patchChangeId the phase's patch change
   * @param liveAt the phase's live time
   * @param active the approval status of an active member
   * @return how many asked members relocated
   */
  @Query(
      """
      SELECT COUNT(r) FROM PatchChangeRelocation r, User u
      WHERE r.patchChangeId = :patchChangeId AND u.id = r.userId
        AND u.approvalStatus = :active AND (u.createdAt IS NULL OR u.createdAt < :liveAt)
      """)
  long countRelocated(
      @Param("patchChangeId") UUID patchChangeId,
      @Param("liveAt") Instant liveAt,
      @Param("active") ApprovalStatus active);

  /**
   * Returns the ids of the members a phase asks who have not chosen in it.
   *
   * @param patchChangeId the phase's patch change
   * @param liveAt the phase's live time
   * @param active the approval status of an active member
   * @return the pending members
   */
  @Query(
      """
      SELECT u.id FROM User u
      WHERE u.approvalStatus = :active AND (u.createdAt IS NULL OR u.createdAt < :liveAt)
        AND NOT EXISTS (
          SELECT r.userId FROM PatchChangeRelocation r
          WHERE r.patchChangeId = :patchChangeId AND r.userId = u.id)
      """)
  List<UUID> findAllPending(
      @Param("patchChangeId") UUID patchChangeId,
      @Param("liveAt") Instant liveAt,
      @Param("active") ApprovalStatus active);
}
