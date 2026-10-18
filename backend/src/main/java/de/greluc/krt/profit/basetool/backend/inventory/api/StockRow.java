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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Lager row as the other modules see it: a read-only live view of the managed row, so a read
 * after one of the Lager's commands sees what that command wrote (plan section 7.6, P4-1 part 3).
 *
 * <p>The view is the managed instance, not a copy: the locks, the persistence context and the
 * optimistic version are the row's own. It has no setter; the Lager changes a row only through
 * {@link StockCommands}, which take the row's id.
 */
public interface StockRow {

  /**
   * Returns the row's id.
   *
   * @return the id
   */
  UUID getId();

  /**
   * Returns the row's optimistic-lock version as the persistence context holds it now.
   *
   * @return the version
   */
  Long getVersion();

  /**
   * Returns the member the stock belongs to.
   *
   * @return the owner
   */
  @NotNull
  User getUser();

  /**
   * Returns the org unit the row is stamped to.
   *
   * @return the org unit, or {@code null} for an ownerless row
   */
  @Nullable
  OrgUnit getOwningOrgUnit();

  /**
   * Returns the material, set on a material row.
   *
   * @return the material, or {@code null} on a game-item row
   */
  @Nullable
  Material getMaterial();

  /**
   * Returns the game item, set on a game-item row.
   *
   * @return the game item, or {@code null} on a material row
   */
  @Nullable
  GameItem getGameItem();

  /**
   * Returns where the stock lies.
   *
   * @return the location
   */
  @NotNull
  Location getLocation();

  /**
   * Returns the quality of a material row.
   *
   * @return the quality, or {@code null} on a game-item row
   */
  @Nullable
  Integer getQuality();

  /**
   * Returns the stock of the row.
   *
   * @return the amount
   */
  Double getAmount();

  /**
   * Returns the stolen marker.
   *
   * @return whether the row is marked stolen
   */
  Boolean getStolen();

  /**
   * Returns whether the row is the holder's personal stock rather than shared in a pool.
   *
   * @return {@code true} for personal stock
   */
  Boolean getPersonal();

  /**
   * Tells whether the row belongs to its lot's personal part: a personal row, or a shared row
   * outside any unit's pool (REQ-INV-060, REQ-XCH-016).
   *
   * @return {@code true} when the row counts as personal stock
   */
  default boolean countsAsPersonal() {
    return Boolean.TRUE.equals(getPersonal()) || getOwningOrgUnit() == null;
  }

  /**
   * Sums the amounts of the row's job-order slices (its job-order allocations must be loaded).
   *
   * @return the total earmarked to job orders
   */
  double jobOrderReserved();

  /**
   * Sums the amounts of the row's mission slices (its mission allocations must be loaded).
   *
   * @return the total earmarked to missions
   */
  double missionReserved();

  /**
   * Sums the amounts of the row's donation slices (its donation allocations must be loaded).
   *
   * @return the total pledged as donations
   */
  double donationReserved();

  /**
   * Tells whether the row has a slice for a job order.
   *
   * @param jobOrderId the job order
   * @return whether a slice for that order exists
   */
  boolean isEarmarkedForJobOrder(@NotNull UUID jobOrderId);

  /**
   * Returns the amount of the row's slice for a job order.
   *
   * @param jobOrderId the job order
   * @return the slice's amount, {@code 0} when the row has no slice for that order
   */
  double jobOrderSliceAmount(@NotNull UUID jobOrderId);
}
