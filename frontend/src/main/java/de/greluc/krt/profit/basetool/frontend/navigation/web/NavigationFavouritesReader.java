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
import de.greluc.krt.profit.basetool.frontend.navigation.client.NavigationBackendClient;
import de.greluc.krt.profit.basetool.frontend.navigation.model.FavouritesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAvailability;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites.Item;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reads the caller's stored favourites and resolves them against the registry (REQ-UI-032): an
 * entry the caller may no longer see in any context is deleted from the store, an entry only the
 * active context does not offer stays and renders greyed.
 *
 * <p>Fails closed: a failed read yields {@link NavigationFavourites#NOT_LOADED}, which renders no
 * favourite and disables the stars.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigationFavouritesReader {

  private final NavigationPreferencesLoader preferences;

  private final NavigationBackendClient client;

  private final NavigationRegistry registry;

  /**
   * Reads and resolves the caller's favourites, deleting those the caller may no longer see.
   *
   * @param access the caller's reachable roles and flags
   * @return the favourites to render; never {@code null}
   */
  @NotNull
  public NavigationFavourites read(@NotNull NavigationAccess access) {
    try {
      NavigationPreferencesResponse stored = preferences.load().orElse(null);
      if (stored == null) {
        return NavigationFavourites.NOT_LOADED;
      }
      Resolution resolution = resolve(stored.favourites(), access);
      if (resolution.gone().isEmpty()) {
        return resolution.view();
      }
      NavigationPreferencesResponse pruned = client.prune(resolution.gone());
      if (pruned == null) {
        return resolution.view();
      }
      preferences.replace(pruned);
      return resolve(pruned.favourites(), access).view();
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (BackendServiceException e) {
      log.debug("Failed to read the navigation favourites", e);
      return NavigationFavourites.NOT_LOADED;
    }
  }

  /**
   * Splits stored favourites into those to render and those to delete.
   *
   * @param stored the stored favourites section
   * @param access the caller's reachable roles and flags
   * @return the favourites to render and the keys to delete
   */
  @NotNull
  Resolution resolve(@NotNull FavouritesResponse stored, @NotNull NavigationAccess access) {
    List<Item> member = new ArrayList<>();
    List<Item> admin = new ArrayList<>();
    List<String> gone = new ArrayList<>();
    for (String key : stored.keys()) {
      Optional<NavigationEntry> entry = registry.starrable(key);
      if (entry.isEmpty()) {
        gone.add(key);
        continue;
      }
      NavigationAvailability availability = registry.availability(entry.get(), access);
      if (availability == NavigationAvailability.GONE) {
        gone.add(key);
      } else {
        add(
            member,
            admin,
            key,
            new Item(entry.get(), availability == NavigationAvailability.AVAILABLE));
      }
    }
    return new Resolution(
        new NavigationFavourites(true, stored.version(), stored.keys(), member, admin), gone);
  }

  private void add(List<Item> member, List<Item> admin, String key, Item item) {
    (registry.isAdminEntry(key) ? admin : member).add(item);
  }

  /**
   * The outcome of {@link #resolve}.
   *
   * @param view the favourites to render
   * @param gone the stored keys the caller may no longer see
   */
  record Resolution(@NotNull NavigationFavourites view, @NotNull List<String> gone) {}
}
