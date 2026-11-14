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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Tells the Lager which rows a pending relocation locks (REQ-PATCH-023, ADR-0383): while a
 * home-location phase is open, the rows created before its live time, or without a creation time,
 * of a holder who has not chosen yet. Implemented by the patch-change module; each call joins the
 * caller's transaction.
 */
public interface StockRelocationLock {

  /**
   * Returns the open home-location phase's live time, the cutoff of the lock.
   *
   * @return the live time, or empty while no phase is open and nothing is locked
   */
  @NotNull
  Optional<Instant> openPhaseLiveAt();

  /**
   * Returns the holders among the given ones who must still relocate in the open phase.
   *
   * @param holders the holders to look at
   * @return the pending holders, empty while no phase is open
   */
  @NotNull
  @Unmodifiable
  Set<UUID> pendingAmong(@NotNull Collection<UUID> holders);

  /**
   * Refuses the current caller's write to rows from before the open phase's live time.
   *
   * @param holders the holders of the rows from before the live time the write changes
   * @throws RuntimeException a {@code 409 RELOCATION_PENDING} problem when one of them is pending
   *     and the caller may not write for them: an exchange client always, a member on the web
   *     unless it is the holder or an admin
   */
  void requireWritable(@NotNull Collection<UUID> holders);
}
