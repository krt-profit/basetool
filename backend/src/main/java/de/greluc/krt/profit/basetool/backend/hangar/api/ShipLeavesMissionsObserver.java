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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Reacts to a ship leaving the missions that carry it, inside the transaction that causes it (plan
 * §5.3); implemented by the mission module, whose units may carry the ship. Every implementation
 * joins the caller's transaction ({@code MANDATORY}).
 */
@ObserverSpi
public interface ShipLeavesMissionsObserver {

  /**
   * A ship leaves every mission unit that carries it, for the given cause; it is called while the
   * ship's row still exists, so for {@link ShipLeaveCause#DELETED} before the ship is deleted.
   *
   * @param shipId the ship that leaves its missions
   * @param cause why it leaves them, carried by the gap notice
   * @return the number of mission units released
   */
  int shipLeaves(@NotNull UUID shipId, @NotNull ShipLeaveCause cause);

  /**
   * Every ship of a departing member is about to be removed by the one set-based delete of the
   * account deletion (REQ-DATA-008); whatever references them lets go of them first, with the cause
   * {@link ShipLeaveCause#DELETED}.
   *
   * <p>Works through set-based statements and projections only: a managed entity that references
   * the member, a ship included, aborts the deletion's flush.
   *
   * @param ownerId the departing member whose ships are deleted
   * @return the number of references released
   */
  int beforeOwnerShipsDelete(@NotNull UUID ownerId);
}
