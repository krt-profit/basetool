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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence of the {@link NavigationPreferences} roots and their sections. */
public interface NavigationPreferencesRepository
    extends JpaRepository<NavigationPreferences, UUID> {

  /**
   * Creates the member's root row when it does not exist yet; a concurrent creator is not an error.
   *
   * @param userId the owner's {@code app_user.id}
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @NativeQuery(
      "INSERT INTO navigation_preferences (user_id) VALUES (:userId) ON CONFLICT (user_id) DO"
          + " NOTHING")
  void insertIfAbsent(@Param("userId") UUID userId);

  /**
   * Bumps the favourites version when it still equals the expected one, locking the root row until
   * the transaction ends.
   *
   * @param userId the owner's {@code app_user.id}
   * @param expected the version the caller last saw
   * @return {@code 1} when the version matched, {@code 0} when it is stale
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE NavigationPreferences p SET p.favouritesVersion = p.favouritesVersion + 1"
          + " WHERE p.userId = :userId AND p.favouritesVersion = :expected")
  int bumpFavouritesVersionIfMatches(
      @Param("userId") UUID userId, @Param("expected") long expected);

  /**
   * Bumps the favourites version without a check, for a change the member did not echo a version
   * for.
   *
   * @param userId the owner's {@code app_user.id}
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE NavigationPreferences p SET p.favouritesVersion = p.favouritesVersion + 1"
          + " WHERE p.userId = :userId")
  void bumpFavouritesVersion(@Param("userId") UUID userId);

  /**
   * Counts the members with at least one favourite.
   *
   * @return the number of members that personalised the favourites
   */
  @Query("SELECT COUNT(p) FROM NavigationPreferences p WHERE SIZE(p.favourites) > 0")
  long countWithFavourites();
}
