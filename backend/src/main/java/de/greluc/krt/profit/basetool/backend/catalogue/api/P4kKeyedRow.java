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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A catalogue row the P4K import matches by GUID and stamps (REQ-P4K-005, REQ-P4K-007); the
 * entities implement it through their Lombok accessors.
 */
public interface P4kKeyedRow {

  /**
   * Returns the row's primary key.
   *
   * @return the id, {@code null} before the row is saved
   */
  UUID getId();

  /**
   * Returns the P4K GUID, the row's identity in the catalogue.
   *
   * @return the GUID, or {@code null} when the P4K never matched the row
   */
  @Nullable
  UUID getP4kUuid();

  /**
   * Sets the P4K GUID.
   *
   * @param p4kUuid the GUID
   */
  void setP4kUuid(@Nullable UUID p4kUuid);

  /**
   * Sets the time of the last P4K match.
   *
   * @param p4kSyncedAt the apply time
   */
  void setP4kSyncedAt(Instant p4kSyncedAt);

  /**
   * Sets the run that last matched the row.
   *
   * @param p4kSeenRunId the run id
   */
  void setP4kSeenRunId(@Nullable UUID p4kSeenRunId);
}
