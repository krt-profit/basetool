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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The members' home-location settings (REQ-PATCH-030, REQ-PATCH-031). Every write is one
 * conditional statement, so a concurrent writer is detected by the database and never overwritten
 * in silence.
 */
@Repository
interface MemberHomeLocationRepository extends JpaRepository<MemberHomeLocation, UUID> {

  /**
   * Stores a member's first choice at version 1.
   *
   * @param userId the member
   * @param locationId the chosen home location
   * @return {@code 1} when stored, {@code 0} when the member has a row already
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO member_home_location (user_id, location_id, version)
          VALUES (:userId, :locationId, 1)
          ON CONFLICT (user_id) DO NOTHING
          """,
      nativeQuery = true)
  int insertFirst(@Param("userId") UUID userId, @Param("locationId") UUID locationId);

  /**
   * Changes a member's choice when the row still has the version the caller read.
   *
   * @param userId the member
   * @param locationId the chosen home location
   * @param version the version the caller read
   * @return {@code 1} when changed, {@code 0} when the row has another version
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE member_home_location
          SET location_id = :locationId, version = version + 1, updated_at = now()
          WHERE user_id = :userId AND version = :version
          """,
      nativeQuery = true)
  int updateAt(
      @Param("userId") UUID userId,
      @Param("locationId") UUID locationId,
      @Param("version") long version);

  /**
   * Sets a member's choice whatever its version, as a relocation does; an unchanged choice keeps
   * its version.
   *
   * @param userId the member
   * @param locationId the home location the relocation chose
   * @return {@code 1} when the choice was stored or changed, {@code 0} when it was already set
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO member_home_location (user_id, location_id, version)
          VALUES (:userId, :locationId, 1)
          ON CONFLICT (user_id) DO UPDATE
          SET location_id = EXCLUDED.location_id,
              version = member_home_location.version + 1,
              updated_at = now()
          WHERE member_home_location.location_id IS DISTINCT FROM EXCLUDED.location_id
          """,
      nativeQuery = true)
  int follow(@Param("userId") UUID userId, @Param("locationId") UUID locationId);
}
