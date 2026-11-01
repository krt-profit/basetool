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
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional writes of the phone tab-bar sections (REQ-UI-032), one version counter per
 * mode: a write bumps its mode's counter with a conditional {@code UPDATE} first, which rejects a
 * stale version and locks the member's root row until the commit.
 */
@Service
@RequiredArgsConstructor
public class NavigationTabBarWriter {

  private static final String REPEATED_KEY = "An entry key may appear only once in a tab bar.";

  private final NavigationPreferencesRepository repository;

  /**
   * Replaces one mode's bar when the echoed version is current.
   *
   * @param userId the owner's {@code app_user.id}
   * @param mode the mode whose bar changes
   * @param slots {@link NavigationLimits#TAB_BAR_SLOTS} entries, {@code null} for an automatic slot
   * @param expectedVersion the mode's tab-bar version the caller last saw
   * @return the stored bar with the bumped version
   * @throws BadRequestException when a key appears twice
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull TabBarResponse replace(
      @NotNull UUID userId,
      @NotNull NavigationMode mode,
      @NotNull List<String> slots,
      long expectedVersion) {
    List<String> chosen = slots.stream().filter(Objects::nonNull).toList();
    if (Set.copyOf(chosen).size() != chosen.size()) {
      throw new BadRequestException(REPEATED_KEY);
    }
    repository.insertIfAbsent(userId);
    if (repository.bumpTabBarVersionIfMatches(
            userId, mode == NavigationMode.MEMBER, expectedVersion)
        == 0) {
      throw new ObjectOptimisticLockingFailureException(NavigationPreferences.class, userId);
    }
    NavigationPreferences preferences = repository.findById(userId).orElseThrow();
    preferences.replaceTabBar(mode, slots);
    repository.saveAndFlush(preferences);
    return response(preferences, mode);
  }

  /**
   * Turns every slot that names a deleted key back to automatic, bumping the version of each bar
   * that changed.
   *
   * @param userId the owner's {@code app_user.id}
   * @param gone the entry keys the member may no longer see
   * @return the tab-bar sections in which a stored slot was cleared
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<NavigationSection> prune(@NotNull UUID userId, @NotNull Set<String> gone) {
    Set<NavigationSection> changed = EnumSet.noneOf(NavigationSection.class);
    for (NavigationMode mode : NavigationMode.values()) {
      NavigationPreferences current = repository.findById(userId).orElse(null);
      if (current == null) {
        break;
      }
      List<String> kept =
          current.tabBarSlots(mode).stream()
              .map(key -> key != null && gone.contains(key) ? null : key)
              .toList();
      if (!kept.equals(current.tabBarSlots(mode))) {
        current.replaceTabBar(mode, kept);
        repository.saveAndFlush(current);
        repository.bumpTabBarVersion(userId, mode == NavigationMode.MEMBER);
        changed.add(NavigationSection.TAB_BAR);
      }
    }
    return changed;
  }

  /**
   * Maps one mode's stored bar to its response.
   *
   * @param preferences the member's root row
   * @param mode the mode
   * @return the slots and the version
   */
  static @NotNull TabBarResponse response(
      @NotNull NavigationPreferences preferences, @NotNull NavigationMode mode) {
    return new TabBarResponse(preferences.tabBarSlots(mode), preferences.tabBarVersion(mode));
  }
}
