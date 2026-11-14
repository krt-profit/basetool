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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A member's profile setting "my home location" as other modules set it (REQ-PATCH-031): the latest
 * home-location choice of a patch change overwrites it.
 */
public interface HomeLocationPreference {

  /**
   * Sets the member's home location to the one a relocation chose, whatever the setting's version,
   * and records the change when the setting changed.
   *
   * @param member the member who relocated
   * @param locationId the curated home location the relocation chose
   * @throws org.springframework.transaction.IllegalTransactionStateException when called outside
   *     the relocation's transaction
   */
  void followRelocation(@NotNull UUID member, @NotNull UUID locationId);
}
