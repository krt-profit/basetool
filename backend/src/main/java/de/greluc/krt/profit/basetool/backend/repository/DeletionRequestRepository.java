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

import de.greluc.krt.profit.basetool.backend.model.DeletionRequest;
import de.greluc.krt.profit.basetool.backend.model.DeletionRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for the members' Art. 17 erasure requests (REQ-SEC-061). */
@Repository
public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, UUID> {

  /**
   * Finds the member's open request, if they have one.
   *
   * <p>At most one can exist — a partial unique index on {@code (user_id) WHERE status = 'PENDING'}
   * is the guarantee, so this returns an {@link Optional} rather than a list.
   *
   * @param userId the member
   * @param status the status to look for (in practice {@link DeletionRequestStatus#PENDING})
   * @return the request, or empty
   */
  Optional<DeletionRequest> findByUserIdAndStatus(UUID userId, DeletionRequestStatus status);

  /**
   * The member's most recent request, whatever its status.
   *
   * <p>Read by the member's own profile page rather than {@link #findByUserIdAndStatus}, and the
   * difference is an obligation rather than a nicety: a <b>refused</b> request carries the admin's
   * reasoning, and Art. 12(4) requires the requester to be told it. A projection that returned only
   * pending requests would leave the member with a notification saying they had been refused and
   * nowhere to read why.
   *
   * @param userId the member
   * @return their latest request, or empty when they have never made one
   */
  Optional<DeletionRequest> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

  /**
   * Lists every request in a status, oldest first, for the admin queue.
   *
   * @param status the status to list
   * @return the matching requests, oldest first
   */
  List<DeletionRequest> findByStatusOrderByCreatedAtAsc(DeletionRequestStatus status);

  /**
   * Counts the requests in a status, backing the {@code basetool_deletion_request_pending_count}
   * gauge (REQ-OBS-011).
   *
   * @param status the status to count
   * @return how many requests are in it
   */
  long countByStatus(DeletionRequestStatus status);

  /**
   * Finds when the longest-waiting request in a status was raised, for the {@code
   * basetool_deletion_request_pending_oldest_age_seconds} gauge.
   *
   * @param status the status to scan
   * @return the earliest {@code createdAt} in that status, or {@code null} when none exists
   */
  @Query("SELECT MIN(d.createdAt) FROM DeletionRequest d WHERE d.status = :status")
  Instant findOldestCreatedAtByStatus(@Param("status") DeletionRequestStatus status);
}
