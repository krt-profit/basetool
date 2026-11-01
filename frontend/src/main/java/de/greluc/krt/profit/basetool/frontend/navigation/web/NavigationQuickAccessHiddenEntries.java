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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHiddenEntries;
import de.greluc.krt.profit.basetool.frontend.navigation.client.NavigationBackendClient;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reads the caller's hidden pages for the quick access and expands them to the entries they take
 * with them (REQ-UI-032). Fails open: when the preferences cannot be read, nothing is left out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigationQuickAccessHiddenEntries implements QuickAccessHiddenEntries {

  private final NavigationBackendClient client;

  private final NavigationRegistry registry;

  @Override
  public @NotNull Set<String> keys() {
    try {
      NavigationPreferencesResponse stored = client.preferences();
      if (stored == null) {
        return Set.of();
      }
      return registry.familyOf(
          stored.hidden().keys().stream()
              .filter(key -> registry.hideable(key).isPresent())
              .collect(Collectors.toSet()));
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (BackendServiceException e) {
      log.debug("Failed to read the hidden navigation pages", e);
      return Set.of();
    }
  }
}
