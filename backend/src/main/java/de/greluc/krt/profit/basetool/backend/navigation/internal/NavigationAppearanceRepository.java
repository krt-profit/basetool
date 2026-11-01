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

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence of the {@link NavigationAppearance} rows. */
public interface NavigationAppearanceRepository extends JpaRepository<NavigationAppearance, UUID> {

  /**
   * Counts the members whose desktop mode is not the default.
   *
   * @return the number of members that personalised the appearance
   */
  @Query("SELECT COUNT(a) FROM NavigationAppearance a WHERE a.desktopMode <> 'DRAWER'")
  long countPersonalised();
}
