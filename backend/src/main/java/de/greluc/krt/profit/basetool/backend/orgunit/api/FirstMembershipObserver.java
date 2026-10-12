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

import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reacts to a member gaining a first or losing a last org-unit membership inside the transaction
 * that makes the change (plan §5.3, §7.6).
 *
 * <p>The membership service calls it after the change and before its transaction commits; every
 * implementation joins that transaction ({@code MANDATORY}), so its writes commit or roll back with
 * the change.
 */
@ObserverSpi
public interface FirstMembershipObserver {

  /**
   * A member who belonged to no org unit joined one.
   *
   * @param userId the member's account
   * @param firstOrgUnit the org unit the member joined
   */
  void onUserGainedFirstOrgUnit(@NotNull UUID userId, @NotNull OrgUnit firstOrgUnit);

  /**
   * A member's last org-unit membership ended.
   *
   * @param userId the member's account
   */
  void onUserLostLastOrgUnit(@NotNull UUID userId);
}
