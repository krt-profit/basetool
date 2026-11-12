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
 * Resolves the per-membership appointments of an org unit as notification recipients. Every method
 * returns only members who hold {@code KRT Member}, since without it no membership confers anything
 * (REQ-NOTIF-024, REQ-SEC-015).
 *
 * <p>Owned by the notification module and implemented by the orgunit module.
 */
public interface OrgUnitRecipientDirectory {

  /**
   * Reads the label for the unit recorded on an event.
   *
   * @param orgUnitId the unit
   * @return the label when the unit still exists
   */
  @NotNull
  Optional<String> unitName(@NotNull UUID orgUnitId);

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

  /**
   * The members who hold a leadership seat in an org unit.
   *
   * @param orgUnitId the org unit
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> leadershipOf(@NotNull UUID orgUnitId);

  /**
   * Resolves functional writers whose current leadership or Logistician grant covers the unit.
   *
   * @param orgUnitId the affected unit
   * @return current writer account ids
   */
  @NotNull
  Set<UUID> writersOf(@NotNull UUID orgUnitId);

  /**
   * The members who hold a leadership seat in any org unit.
   *
   * @return their user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> allSeatHolders();
}
