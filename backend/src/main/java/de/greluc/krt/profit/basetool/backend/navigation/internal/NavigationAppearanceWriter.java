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
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional reads and writes of the "Darstellung" section; {@link
 * NavigationPreferencesService} wraps them with the write metrics.
 *
 * <p>A write bumps the appearance counter with a conditional {@code UPDATE} first, which rejects a
 * stale version and locks the member's root row until the commit, so racing writers serialise and a
 * favourites write is never blocked by this version.
 */
@Service
@RequiredArgsConstructor
public class NavigationAppearanceWriter {

  private final NavigationPreferencesRepository preferences;
  private final NavigationAppearanceRepository appearances;

  /**
   * Reads the member's appearance; a member who never chose holds the default.
   *
   * @param userId the owner's {@code app_user.id}
   * @param version the appearance version of the member's root row
   * @return the stored mode with the version
   */
  @NotNull
  AppearanceResponse read(@NotNull UUID userId, long version) {
    return appearances
        .findById(userId)
        .map(row -> new AppearanceResponse(row.getDesktopMode(), row.isShortcutsEnabled(), version))
        .orElseGet(() -> new AppearanceResponse(DesktopMode.DRAWER, true, version));
  }

  /**
   * Replaces the section when the echoed version is current.
   *
   * @param userId the owner's {@code app_user.id}
   * @param mode the chosen desktop mode
   * @param shortcutsEnabled whether the navigation shortcuts are on
   * @param expectedVersion the appearance version the caller last saw
   * @return the stored appearance with the bumped version
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull AppearanceResponse replace(
      @NotNull UUID userId,
      @NotNull DesktopMode mode,
      boolean shortcutsEnabled,
      long expectedVersion) {
    preferences.insertIfAbsent(userId);
    if (preferences.bumpAppearanceVersionIfMatches(userId, expectedVersion) == 0) {
      throw new ObjectOptimisticLockingFailureException(NavigationPreferences.class, userId);
    }
    NavigationAppearance row =
        appearances
            .findById(userId)
            .orElseGet(() -> new NavigationAppearance(userId, mode, shortcutsEnabled));
    row.setDesktopMode(mode);
    row.setShortcutsEnabled(shortcutsEnabled);
    appearances.saveAndFlush(row);
    return new AppearanceResponse(
        mode,
        shortcutsEnabled,
        preferences.findById(userId).orElseThrow().getAppearanceVersion());
  }
}
