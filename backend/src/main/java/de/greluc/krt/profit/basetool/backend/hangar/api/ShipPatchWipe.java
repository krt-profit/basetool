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
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Removes the ships a game patch wiped (REQ-PATCH-015): every member's ships bought in-game,
 * created before the patch's live time. A ship of unknown origin, a pledged one and one whose
 * origin is locked stay; a ship without a creation time counts as created before the live time.
 */
public interface ShipPatchWipe {

  /**
   * Counts the ships {@link #wipe} would remove now, without writing.
   *
   * @param liveAt the patch's live time
   * @return the ships and their owners
   */
  @NotNull
  ShipWipeCount countWipeable(@NotNull Instant liveAt);

  /**
   * Deletes the ships through the ordinary deletion path inside the caller's transaction, so a
   * mission unit that carried one is released with its notice; records one summary event in the
   * hangar's trail and refreshes the open hangar pages after the commit.
   *
   * @param liveAt the patch's live time
   * @param patchChangeId the patch change that wiped the ships
   * @param patchLabel its label, the summary event's subject label
   * @return the ships removed and their owners
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active
   */
  @NotNull
  ShipWipeCount wipe(
      @NotNull Instant liveAt, @NotNull UUID patchChangeId, @NotNull String patchLabel);
}
