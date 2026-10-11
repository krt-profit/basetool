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

package de.greluc.krt.profit.basetool.backend.platform.api;

import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A module's part of the admin account merge (REQ-SEC-045): it names the member-referencing columns
 * its module holds, those that follow the member and those that stay with the act, and moves the
 * former from the source account to the target, inside the merge's transaction.
 *
 * <p>The orchestrator asks every participant to {@link #assertMergeable} first, then runs {@link
 * #reassign} by {@link #position()} and reports the moved rows in that order. Implementations join
 * the caller's transaction ({@code MANDATORY}).
 */
@ObserverSpi
public interface UserReassignmentParticipant {

  /**
   * The rows one column moved.
   *
   * @param column the column as {@code table.column}
   * @param rows the rows re-pointed
   */
  record Moved(@NotNull String column, int rows) {}

  /**
   * Returns where the participant runs, ascending; the order of the reported columns is part of the
   * merge's audit row, and two participants never share a value.
   *
   * @return the position
   */
  int position();

  /**
   * Returns every column of this module that follows the member to the surviving account.
   *
   * @return the declarations, in the order they are moved and reported
   */
  @NotNull
  List<OwnedRows> followsTheMember();

  /**
   * Returns every member-referencing column of this module that records an act rather than a
   * belonging and therefore stays with the source account, as {@code table.column}.
   *
   * @return the qualified column names
   */
  @NotNull
  List<String> staysWithTheAct();

  /**
   * Refuses the merge when this module cannot combine the two accounts, before anything moves.
   *
   * @param source the account being emptied
   * @param target the account being kept
   */
  void assertMergeable(@NotNull UUID source, @NotNull UUID target);

  /**
   * Re-points this module's {@link #followsTheMember} columns from the source to the target, after
   * dropping the source rows the target already holds under a unique key.
   *
   * @param source the account being emptied
   * @param target the account being kept
   * @return one entry per column that had a row to move or to drop, in declaration order
   */
  @NotNull
  List<Moved> reassign(@NotNull UUID source, @NotNull UUID target);
}
