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

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the people around one mission as notification recipients.
 *
 * <p>Owned by the notification module and implemented by the mission module.
 */
public interface MissionRecipientDirectory {

  /**
   * The registered members signed up for a mission; guest participants have no inbox and are left
   * out.
   *
   * @param missionId the mission
   * @param onlyNotCheckedIn {@code true} to keep only participants who have not checked in
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> participantsOf(@NotNull UUID missionId, boolean onlyNotCheckedIn);

  /**
   * The mission's owner and its co-managers.
   *
   * @param missionId the mission
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> leadershipOf(@NotNull UUID missionId);
}
