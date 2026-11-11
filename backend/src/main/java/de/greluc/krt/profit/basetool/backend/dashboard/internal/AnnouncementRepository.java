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

package de.greluc.krt.profit.basetool.backend.dashboard.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Announcement. */
@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

  /** Returns the first matching {@code OrderByUpdatedAtDesc} (limit 1). */
  Optional<Announcement> findTopByOrderByUpdatedAtDesc();

  /** Returns the active singleton, excluding retained non-current rows. */
  Optional<Announcement> findBySingletonTrue();

  /** Creates the singleton on the write path, preserving a concurrent creator's row. */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO announcement (id, content, version, created_at, updated_at, singleton)
          VALUES (gen_random_uuid(), NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, TRUE)
          ON CONFLICT (singleton) WHERE singleton DO NOTHING
          """,
      nativeQuery = true)
  void ensureSingleton();

  /**
   * Locks the database-enforced singleton for the current write transaction.
   *
   * @return the singleton, or empty when none exists
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM Announcement a WHERE a.singleton = true")
  Optional<Announcement> lockSingleton();
}
