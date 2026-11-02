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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;

/** Persistence of the {@link NavigationDashboardWidget} rows. */
public interface DashboardWidgetRepository extends JpaRepository<NavigationDashboardWidget, UUID> {

  /**
   * Lists the instances of a layout in display order.
   *
   * @param layoutId the layout
   * @return the instances, by position
   */
  List<NavigationDashboardWidget> findByLayoutIdOrderByPositionAsc(UUID layoutId);

  /**
   * Finds one instance of a layout.
   *
   * @param id the instance
   * @param layoutId the layout
   * @return the instance, empty when the layout holds none with this id
   */
  Optional<NavigationDashboardWidget> findByIdAndLayoutId(UUID id, UUID layoutId);

  /**
   * Deletes every instance of a layout.
   *
   * @param layoutId the layout
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @NativeQuery("DELETE FROM navigation_dashboard_widget WHERE layout_id = :layoutId")
  void deleteAllOfLayout(@Param("layoutId") UUID layoutId);

  /**
   * Bumps an instance's option version when it still equals the expected one.
   *
   * @param id the instance
   * @param expected the version the caller last saw
   * @return {@code 1} when the version matched, {@code 0} when it is stale
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @NativeQuery(
      "UPDATE navigation_dashboard_widget SET options_version = options_version + 1 WHERE id = :id"
          + " AND options_version = :expected")
  int bumpOptionsVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);
}
