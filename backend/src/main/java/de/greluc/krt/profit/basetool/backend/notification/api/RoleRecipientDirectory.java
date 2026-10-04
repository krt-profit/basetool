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

package de.greluc.krt.profit.basetool.backend.notification.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves global-role recipients of notification rules from the role catalogue and the synced
 * {@code user_roles} mirror (REQ-NOTIF-008).
 *
 * <p>Owned by the notification module and implemented by the identity module.
 */
public interface RoleRecipientDirectory {

  /**
   * Every holder of a global role.
   *
   * @param roleCode the stable role code, for example {@code ADMIN}
   * @return the holders' user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> holdersOfRole(@NotNull String roleCode);

  /**
   * The holders of a global role who are members of one org unit.
   *
   * @param roleCode the stable role code
   * @param orgUnitId the org unit the holders must belong to
   * @return the matching user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> holdersOfRoleInOrgUnit(@NotNull String roleCode, @NotNull UUID orgUnitId);

  /**
   * Looks a role code up in the role catalogue, ignoring case.
   *
   * @param roleCode the code as submitted
   * @return the catalogue's spelling of the code, or empty when the catalogue does not know it
   */
  @NotNull
  Optional<String> catalogueRoleCode(@NotNull String roleCode);
}
