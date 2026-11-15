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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A merge participant whose module's writers take a lock before they reference an account row, so
 * the merge takes that lock before it locks the two account rows (REQ-SEC-046).
 */
@ObserverSpi
public interface PreparedUserReassignmentParticipant extends UserReassignmentParticipant {

  /**
   * Acquires the module's locks in the merge's transaction, before the merge locks the account
   * rows.
   *
   * @param source the account being emptied
   * @param target the account being kept
   */
  void prepareMerge(@NotNull UUID source, @NotNull UUID target);
}
