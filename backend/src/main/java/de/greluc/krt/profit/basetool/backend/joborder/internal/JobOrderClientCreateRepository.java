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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Serialises and finds the aUEC orders members create through clients (REQ-OXCH-004). */
interface JobOrderClientCreateRepository extends Repository<JobOrder, UUID> {

  /** The first key of the client-create advisory locks: {@code 'OXCR'} as a 32-bit integer. */
  int CLIENT_CREATE_LOCK_SPACE = 0x4F584352;

  /**
   * Takes the member's client-create lock, which the transaction holds until it ends, so two client
   * creates of one member look up their key, count and insert one after the other.
   *
   * <p>The two-key advisory lock space {@value #CLIENT_CREATE_LOCK_SPACE} is the client creates'
   * alone; members whose ids hash alike only wait for each other.
   *
   * @param member the creating member's user id
   * @return always one
   */
  @Query(
      value =
          "SELECT 1 FROM pg_advisory_xact_lock("
              + CLIENT_CREATE_LOCK_SPACE
              + ", hashtext(CAST(:member AS text)))",
      nativeQuery = true)
  int lockMember(@Param("member") UUID member);

  /**
   * Finds the order a client created for a member under an idempotency key.
   *
   * @param member the creating member's user id
   * @param clientId the registry client id
   * @param idempotencyKey the client's key of the create
   * @return the order's id, or empty when the client created none under that key
   */
  @Query(
      "SELECT o.id FROM JobOrder o WHERE o.createdByMemberId = :member"
          + " AND o.createdByClientId = :clientId AND o.createIdempotencyKey = :idempotencyKey")
  Optional<UUID> findCreated(
      @Param("member") UUID member,
      @Param("clientId") String clientId,
      @Param("idempotencyKey") String idempotencyKey);
}
