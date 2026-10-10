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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Snapshots the holders a membership change can make responsible, before the change, and records
 * the differences after it (plan §5.3). Implemented by the bank for its account responsibilities
 * (REQ-BANK-031).
 */
public interface ResponsibleHolderTracker {

  /**
   * Snapshots the responsible holders of everything an org unit's memberships decide.
   *
   * @param orgUnitId the org unit whose memberships are about to change
   * @return the responsible holder ids per tracked item, to pass back after the change
   */
  @NotNull
  Map<UUID, Set<UUID>> snapshotResponsibleHolders(@NotNull UUID orgUnitId);

  /**
   * Snapshots the responsible holders of everything a member's memberships decide.
   *
   * @param userId the member whose memberships are about to change
   * @return the responsible holder ids per tracked item, to pass back after the change
   */
  @NotNull
  Map<UUID, Set<UUID>> snapshotResponsibleHoldersForUser(@NotNull UUID userId);

  /**
   * Compares a snapshot with the current responsible holders and records every change.
   *
   * @param before the snapshot taken before the membership change
   */
  void recordResponsibleHolderChanges(@NotNull Map<UUID, Set<UUID>> before);
}
