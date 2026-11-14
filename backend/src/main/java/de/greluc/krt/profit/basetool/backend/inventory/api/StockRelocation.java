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
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Moves a holder's Lager rows from before an instant to another location in place (REQ-PATCH-025,
 * ADR-0383): every row keeps its identity, quantity, quality, markers, pool, earmarks, pledges and
 * offers; nothing merges and no material promise is credited again.
 */
public interface StockRelocation {

  /**
   * Counts the holder's rows created before an instant, or without a creation time, per current
   * location and pool.
   *
   * @param holder the holder
   * @param createdBefore the instant
   * @return one count per location and pool, own rows with no pool
   */
  @NotNull
  @Unmodifiable
  List<StockRelocationCount> countByLocation(@NotNull UUID holder, @NotNull Instant createdBefore);

  /**
   * Moves the holder's rows created before an instant, or without a creation time, to the target
   * location, inside the caller's transaction. It takes the shared hierarchy lock, then the lot
   * locks of the old and the new lots in ascending order, then the rows; it validates no linked job
   * order, because a relocation is a game fact and no order write (REQ-ORDERS-055). Rows already at
   * the target stay and are not counted. Every moved row's version advances; the Lager's open pages
   * refresh after the commit, also when nothing moved, because the holder's lock ends.
   *
   * @param holder the holder
   * @param createdBefore the instant
   * @param targetLocationId the target location
   * @return how many rows moved
   */
  int relocate(
      @NotNull UUID holder, @NotNull Instant createdBefore, @NotNull UUID targetLocationId);

  /**
   * Tells the Lager's open pages after the commit that the relocation lock changed, so their „Umzug
   * ausstehend" markers follow: a phase opened or ended.
   */
  void announceLockChange();
}
