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

  /**
   * Returns when the P4K sweep stamped the row as gone from the game (REQ-P4K-011).
   *
   * @return the stamp, or {@code null} while the catalogue lists the row
   */
  @Nullable
  Instant getP4kDeletedAt();

  /**
   * Sets or clears the sweep's stamp.
   *
   * @param p4kDeletedAt the stamp, or {@code null} when the row reappeared
   */
  void setP4kDeletedAt(@Nullable Instant p4kDeletedAt);

  /**
   * Returns when an admin hid the row through a review case (REQ-P4K-016).
   *
   * @return the time of the hide, or {@code null}
   */
  @Nullable
  Instant getP4kHiddenAt();

  /**
   * Hides the row through a review case.
   *
   * @param p4kHiddenAt the time of the hide
   */
  void setP4kHiddenAt(@Nullable Instant p4kHiddenAt);

  /**
   * Whether the row is out of the game or hidden, so it leaves every picker and its references show
   * the "no longer in the game" notice (REQ-P4K-013).
   *
   * @return {@code true} when the sweep stamped it or an admin hid it
   */
  default boolean isP4kRetired() {
    return getP4kDeletedAt() != null || getP4kHiddenAt() != null;
  }
}
