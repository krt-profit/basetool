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
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link P4kReviewCase}. */
public interface P4kReviewCaseRepository extends JpaRepository<P4kReviewCase, UUID> {

  /**
   * Finds the case of one record and reason.
   *
   * @param kind the kind
   * @param guid the P4K GUID
   * @param reason the reason
   * @return the case, if one exists
   */
  Optional<P4kReviewCase> findByKindAndGuidAndReason(
      P4kKind kind, UUID guid, P4kReviewReason reason);

  /**
   * Finds the cases of some kinds and P4K GUIDs, whatever their reason.
   *
   * @param kinds the kinds
   * @param guids the GUIDs
   * @return the cases
   */
  List<P4kReviewCase> findByKindInAndGuidIn(Collection<P4kKind> kinds, Collection<UUID> guids);

  /**
   * Finds the cases of one kind that an admin resolved by assigning a row, whatever their reason.
   *
   * @param kind the kind
   * @param resolution the resolution, {@code ASSIGN}
   * @return the cases, oldest first
   */
  List<P4kReviewCase> findByKindAndResolutionAndRowIdIsNotNullOrderByCreatedAtAsc(
      P4kKind kind, P4kReviewResolution resolution);

  /**
   * Pages the cases of one status, oldest first (REQ-P4K-016).
   *
   * @param status the status
   * @param pageable the page
   * @return the page of cases
   */
  Page<P4kReviewCase> findByStatusOrderByCreatedAtAscIdAsc(
      P4kReviewStatus status, Pageable pageable);

  /**
   * Counts the cases of one status and kind (REQ-OBS-022).
   *
   * @param status the status
   * @param kind the kind
   * @return the number of cases
   */
  long countByStatusAndKind(P4kReviewStatus status, P4kKind kind);

  /**
   * Returns when the oldest case of one status was opened (REQ-OBS-022).
   *
   * @param status the status
   * @return the earliest creation time, or {@code null} when no case has the status
   */
  @Query("SELECT MIN(c.createdAt) FROM P4kReviewCase c WHERE c.status = :status")
  @Nullable
  Instant findOldestCreatedAtByStatus(@Param("status") P4kReviewStatus status);

  /**
   * Reads the keys of cases an administrator closed. A case closed by a run can reopen.
   *
   * @return the keys of the admin-closed cases
   */
  @Query(
      "SELECT new de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewKey("
          + "c.kind, c.guid, c.reason) FROM P4kReviewCase c"
          + " WHERE c.status = de.greluc.krt.profit.basetool.backend.catalogue.internal"
          + ".P4kReviewStatus.REVIEWED"
          + " AND c.resolution <> de.greluc.krt.profit.basetool.backend.catalogue.internal"
          + ".P4kReviewResolution.RESOLVED")
  Set<P4kReviewKey> findAdminClosedKeys();

  /**
   * Finds other open cases whose catalogue references follow a retired row in a merge.
   *
   * @param kind the review kind that owns the reference
   * @param retiredId the catalogue row being retired
   * @param activeCaseId the case whose proposal is being applied
   * @return the open cases that reference the retired row
   */
  @Query(
      """
      SELECT c FROM P4kReviewCase c
      WHERE c.kind = :kind
        AND c.status = de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kReviewStatus.OPEN
        AND c.id <> :activeCaseId
        AND (c.rowId = :retiredId OR c.otherRowId = :retiredId)
      """)
  List<P4kReviewCase> findOpenReferences(
      @Param("kind") P4kKind kind,
      @Param("retiredId") UUID retiredId,
      @Param("activeCaseId") UUID activeCaseId);

  /**
   * Finds the cases of one kind and set of reasons, whatever their status.
   *
   * @param kind the kind
   * @param reasons the reasons
   * @return the cases
   */
  List<P4kReviewCase> findByKindAndReasonIn(P4kKind kind, Collection<P4kReviewReason> reasons);

  /**
   * Reads the ship corrections waiting for a compatible source import.
   *
   * @return the pending corrections
   */
  List<P4kReviewCase> findByShipRepairRowIdIsNotNull();

  /**
   * Checks whether another confirmed repair already targets a ship.
   *
   * @param shipRepairRowId the ship
   * @return whether a repair is pending for the ship
   */
  boolean existsByShipRepairRowId(UUID shipRepairRowId);
}
