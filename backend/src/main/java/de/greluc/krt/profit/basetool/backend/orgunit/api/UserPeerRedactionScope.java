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

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Decides which users the caller reads without the peer redaction; implemented by the scope module
 * so that the org-unit module does not depend on it (REQ-SEC-007).
 */
public interface UserPeerRedactionScope {

  /**
   * Selects the users whose full record the caller may read.
   *
   * @param userIds the users to classify; never {@code null}
   * @return the subset the caller holds the Logistician grant for through at least one shared unit
   *     (all of them for an admin)
   */
  @NotNull
  Set<UUID> usersWithUnredactedRead(@NotNull Collection<UUID> userIds);
}
