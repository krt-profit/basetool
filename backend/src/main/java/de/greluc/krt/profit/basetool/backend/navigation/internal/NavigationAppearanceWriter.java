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

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
        .map(row -> response(row, version))
        .orElseGet(() -> new AppearanceResponse(DesktopMode.DRAWER, true, null, null, version));
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
    NavigationAppearance row = rowOf(userId);
    row.setDesktopMode(mode);
    row.setShortcutsEnabled(shortcutsEnabled);
    appearances.saveAndFlush(row);
    return response(row, currentVersion(userId));
  }

  /**
   * Replaces the start page of one mode when the echoed version is current; the other mode, the
   * desktop mode and the shortcut switch stay.
   *
   * @param userId the owner's {@code app_user.id}
   * @param mode the mode whose start page changes
   * @param entryKey the entry key of the new start page, {@code null} for the dashboard
   * @param expectedVersion the appearance version the caller last saw
   * @return the stored appearance with the bumped version
   * @throws BadRequestException when the key is malformed
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull AppearanceResponse replaceStartPage(
      @NotNull UUID userId,
      @NotNull NavigationMode mode,
      @Nullable String entryKey,
      long expectedVersion) {
    requireEntryKey(entryKey);
    preferences.insertIfAbsent(userId);
    if (preferences.bumpAppearanceVersionIfMatches(userId, expectedVersion) == 0) {
      throw new ObjectOptimisticLockingFailureException(NavigationPreferences.class, userId);
    }
    NavigationAppearance row = rowOf(userId);
    row.setStartPage(mode, entryKey);
    appearances.saveAndFlush(row);
    return response(row, currentVersion(userId));
  }

  /**
   * Clears every start page that names one of the given keys, bumping the appearance version when
   * one went.
   *
   * @param userId the owner's {@code app_user.id}
   * @param keys the entry keys the member may no longer see
   * @return {@code true} when a start page was cleared
   */
  @Transactional
  public boolean prune(@NotNull UUID userId, @NotNull Collection<String> keys) {
    Optional<NavigationAppearance> stored = appearances.findById(userId);
    if (stored.isEmpty() || !stored.get().clearStartPages(keys)) {
      return false;
    }
    appearances.saveAndFlush(stored.get());
    preferences.bumpAppearanceVersion(userId);
    return true;
  }

  private NavigationAppearance rowOf(UUID userId) {
    return appearances
        .findById(userId)
        .orElseGet(() -> new NavigationAppearance(userId, DesktopMode.DRAWER, true));
  }

  private long currentVersion(UUID userId) {
    return preferences.findById(userId).orElseThrow().getAppearanceVersion();
  }

  private static AppearanceResponse response(NavigationAppearance row, long version) {
    return new AppearanceResponse(
        row.getDesktopMode(),
        row.isShortcutsEnabled(),
        row.startPageOf(NavigationMode.MEMBER),
        row.startPageOf(NavigationMode.ADMIN),
        version);
  }

  private static void requireEntryKey(String entryKey) {
    if (entryKey != null
        && (entryKey.length() > NavigationLimits.MAX_ENTRY_KEY_LENGTH
            || !entryKey.matches(NavigationLimits.ENTRY_KEY_PATTERN))) {
      throw new BadRequestException("The start page is not an entry key.");
    }
  }
}
