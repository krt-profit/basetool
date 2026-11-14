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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Moves an owner's ships from before an instant to another location (REQ-PATCH-025, ADR-0383): each
 * moved ship's version advances, so a stale web form or exchange client cannot write the old
 * location back.
 */
public interface ShipRelocation {

  /**
   * Counts the owner's ships created before an instant, or without a creation time, per current
   * location.
   *
   * @param owner the owner
   * @param createdBefore the instant
   * @return one count per location, ships without a location under a {@code null} location
   */
  @NotNull
  @Unmodifiable
  List<ShipRelocationCount> countByLocation(@NotNull UUID owner, @NotNull Instant createdBefore);

  /**
   * Moves the owner's ships created before an instant, or without a creation time, to the target
   * location inside the caller's transaction; ships already there stay and are not counted. The
   * owner's and the units' hangar pages refresh after the commit.
   *
   * @param owner the owner
   * @param createdBefore the instant
   * @param targetLocationId the target location
   * @return how many ships moved
   */
  int relocate(@NotNull UUID owner, @NotNull Instant createdBefore, @NotNull UUID targetLocationId);
}
