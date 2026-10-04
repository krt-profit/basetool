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
 * Resolves the per-membership appointments of an org unit as notification recipients.
 *
 * <p>Owned by the notification module and implemented by the orgunit module.
 */
public interface OrgUnitRecipientDirectory {

  /**
   * The members flagged as Lead of an org unit.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> leadsOf(@NotNull UUID orgUnitId);

  /**
   * The members flagged as Logistician of an org unit.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> logisticiansOf(@NotNull UUID orgUnitId);

  /**
   * The members flagged as Mission Manager of an org unit.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> missionManagersOf(@NotNull UUID orgUnitId);
}
