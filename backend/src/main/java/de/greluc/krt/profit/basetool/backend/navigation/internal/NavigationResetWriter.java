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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional full reset of one member's navigation preferences (REQ-UI-032, REQ-UI-034);
 * {@link NavigationPreferencesService} wraps it with the write metrics.
 *
 * <p>The reset takes no version: it is the member's explicit wish to start over. Every section's
 * version moves on instead, so a tab that still holds an older one conflicts on its next write
 * rather than bringing the cleared state back.
 */
@Service
@RequiredArgsConstructor
public class NavigationResetWriter {

  private final NavigationPreferencesRepository preferences;
  private final NavigationAppearanceRepository appearances;
  private final DashboardLayoutRepository layouts;
  private final DashboardWidgetRepository widgets;

  /**
   * Clears favourites, hidden pages, order, tab bars, "Darstellung" with both start pages and both
   * dashboards, and bumps the version of every section.
   *
   * @param userId the owner's {@code app_user.id}
   */
  @Transactional
  public void resetAll(@NotNull UUID userId) {
    preferences.insertIfAbsent(userId);
    NavigationPreferences root = preferences.findById(userId).orElseThrow();
    root.clearAll();
    preferences.saveAndFlush(root);
    appearances.deleteById(userId);
    appearances.flush();
    for (DashboardMode mode : DashboardMode.values()) {
      layouts.insertIfAbsent(UUID.randomUUID(), userId, mode.name());
      NavigationDashboardLayout layout =
          layouts.findByPreferencesIdAndMode(userId, mode).orElseThrow();
      widgets.deleteAllOfLayout(layout.getId());
      layouts.resetToDefault(layout.getId());
    }
    preferences.bumpFavouritesVersion(userId);
    preferences.bumpHiddenVersion(userId);
    preferences.bumpAppearanceVersion(userId);
    for (NavigationMode mode : NavigationMode.values()) {
      preferences.bumpOrderVersion(userId, mode);
      preferences.bumpTabBarVersion(userId, mode == NavigationMode.MEMBER);
    }
  }
}
