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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence of the {@link NavigationDashboardLayout} rows. */
public interface DashboardLayoutRepository extends JpaRepository<NavigationDashboardLayout, UUID> {

  /**
   * Finds the member's layout of a mode.
   *
   * @param preferencesId the owner's root row
   * @param mode the mode
   * @return the layout, empty when the member never wrote it
   */
  Optional<NavigationDashboardLayout> findByPreferencesIdAndMode(
      UUID preferencesId, DashboardMode mode);

  /**
   * Creates the layout row when it does not exist yet; a concurrent creator is not an error.
   *
   * @param id the id of the new row
   * @param preferencesId the owner's root row
   * @param mode the mode's name
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @NativeQuery(
      "INSERT INTO navigation_dashboard_layout (id, preferences_id, mode) VALUES (:id,"
          + " :preferencesId, :mode) ON CONFLICT (preferences_id, mode) DO NOTHING")
  void insertIfAbsent(
      @Param("id") UUID id, @Param("preferencesId") UUID preferencesId, @Param("mode") String mode);

  /**
   * Bumps the layout version when it still equals the expected one, locking the row until the
   * transaction ends.
   *
   * @param preferencesId the owner's root row
   * @param mode the mode
   * @param expected the version the caller last saw
   * @param customised whether the stored widgets replace the default afterwards
   * @return {@code 1} when the version matched, {@code 0} when it is stale
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @NativeQuery(
      "UPDATE navigation_dashboard_layout SET layout_version = layout_version + 1, customised ="
          + " :customised WHERE preferences_id = :preferencesId AND mode = :mode AND"
          + " layout_version = :expected")
  int bumpIfMatches(
      @Param("preferencesId") UUID preferencesId,
      @Param("mode") String mode,
      @Param("expected") long expected,
      @Param("customised") boolean customised);

  /**
   * Bumps the layout version without a check, for a change the member did not echo a version for.
   *
   * @param id the layout
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @NativeQuery(
      "UPDATE navigation_dashboard_layout SET layout_version = layout_version + 1 WHERE id = :id")
  void bump(@Param("id") UUID id);

  /**
   * Counts the layouts the member changed from the default.
   *
   * @return the number of customised layouts
   */
  @Query("SELECT COUNT(l) FROM NavigationDashboardLayout l WHERE l.customised = TRUE")
  long countCustomised();
}
