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

import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import de.greluc.krt.profit.basetool.backend.model.Role;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reacts to a member's roles being replaced by the identity reconciliation, inside the transaction
 * that replaces them (plan §5.3).
 *
 * <p>Every implementation joins that transaction ({@code MANDATORY}). The org-unit module checks
 * the new roles against the member's leadership seats.
 */
@ObserverSpi
public interface RolesChangedObserver {

  /**
   * A member's stored roles were replaced.
   *
   * @param userId the member
   * @param roles the roles the member holds now
   */
  void onRolesChanged(@NotNull UUID userId, @NotNull Set<Role> roles);
}
