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

package de.greluc.krt.profit.basetool.backend.identity.api;

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Staffel visibility of the current caller, as the identity module's user queries need it;
 * owned by identity and implemented by the scope module (plan §5.3).
 *
 * <p>Both questions are answered from the request's resolved scope, exactly as the scope module
 * answers them for every other domain.
 */
public interface SquadronVisibility {

  /**
   * Whether the caller may read data owned by a Staffel.
   *
   * @param squadronId the Staffel (org-unit) id
   * @return {@code true} iff the caller may see that Staffel's data
   */
  boolean canSeeSquadron(@NotNull UUID squadronId);

  /**
   * The Staffel ids the admin user list, the search and the promotion queries filter on.
   *
   * @return the id set, or {@code null} for the unfiltered all-scope of admins and leadership
   */
  @Nullable
  Set<UUID> currentUserListScopeSquadronIds();

  /**
   * The Staffel ids whose members the caller may evaluate and manage as an officer, narrowed to the
   * pinned unit and never wider than the caller's officer reach.
   *
   * @return the id set, empty without officer reach, or {@code null} for the unrestricted admin
   *     scope
   */
  @Nullable
  Set<UUID> currentOfficerReadScope();
}
