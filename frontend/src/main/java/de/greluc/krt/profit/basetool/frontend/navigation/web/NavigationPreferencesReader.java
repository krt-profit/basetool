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
import de.greluc.krt.profit.basetool.frontend.navigation.model.HiddenResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAvailability;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationGroup;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationHidden;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationOrder;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPersonalisation;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderModeResponse;
import de.greluc.krt.profit.basetool.frontend.navigation.model.OrderResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Reads the caller's stored favourites and hidden pages and resolves them against the registry
 * (REQ-UI-032): an entry the caller may no longer see in any context, and a key that is no longer a
 * favouritable or hideable entry, is deleted from the store; an entry only the active context does
 * not offer stays and renders greyed.
 *
 * <p>Fails closed: a failed read yields {@link NavigationPersonalisation#NOT_LOADED}, which renders
 * no favourite, hides no page and disables the controls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigationPreferencesReader {

  private final NavigationBackendClient client;

  private final NavigationRegistry registry;

  /**
   * Reads and resolves the caller's preferences, deleting the entries the caller may no longer see.
   *
   * @param access the caller's reachable roles and flags
   * @return the preferences to render; never {@code null}
   */
  @NotNull
  public NavigationPersonalisation read(@NotNull NavigationAccess access) {
    try {
      NavigationPreferencesResponse stored = client.preferences();
      if (stored == null) {
        return NavigationPersonalisation.NOT_LOADED;
      }
      Resolution resolution = resolve(stored, access);
      if (resolution.gone().isEmpty()) {
        return resolution.view();
      }
      NavigationPreferencesResponse pruned = client.prune(resolution.gone());
      return pruned == null ? resolution.view() : resolve(pruned, access).view();
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (BackendServiceException e) {
      log.debug("Failed to read the navigation preferences", e);
      return NavigationPersonalisation.NOT_LOADED;
    }
  }

  /**
   * Splits the stored sections into the entries to render and the keys to delete.
   *
   * @param stored the stored sections
   * @param access the caller's reachable roles and flags
   * @return the preferences to render and the keys to delete
   */
  @NotNull
  Resolution resolve(
      @NotNull NavigationPreferencesResponse stored, @NotNull NavigationAccess access) {
    List<String> gone = new ArrayList<>();
    NavigationFavourites favourites = resolveFavourites(stored.favourites(), access, gone);
    NavigationHidden hidden = resolveHidden(stored.hidden(), access, gone);
    NavigationOrder order = resolveOrder(stored.order(), access, gone);
    return new Resolution(
        new NavigationPersonalisation(favourites, hidden, order),
        gone.stream().distinct().toList());
  }

  private NavigationOrder resolveOrder(
      OrderResponse stored, NavigationAccess access, List<String> gone) {
    return new NavigationOrder(
        resolveOrder(stored.member(), registry.main(), access, gone),
        resolveOrder(stored.admin(), registry.admin(), access, gone));
  }

  private NavigationOrder.Mode resolveOrder(
      OrderModeResponse stored,
      List<NavigationGroup> groups,
      NavigationAccess access,
      List<String> gone) {
    Set<String> liveGroups =
        groups.stream()
            .filter(group ->
                group.entries().stream()
                    .anyMatch(entry ->
                        registry.availability(entry, access) != NavigationAvailability.GONE))
            .map(NavigationGroup::key)
            .collect(Collectors.toSet());
    Map<String, List<String>> kept = new LinkedHashMap<>();
    stored
        .scopes()
        .forEach((scope, keys) -> {
          boolean groupScope = NavigationOrder.GROUPS_SCOPE.equals(scope);
          List<String> live =
              keys.stream()
                  .filter(key -> {
                    boolean known =
                        groupScope
                            ? liveGroups.contains(key)
                            : availability(registry.entry(key), access)
                                != NavigationAvailability.GONE;
                    if (!known) {
                      gone.add(key);
                    }
                    return known;
                  })
                  .toList();
          if (!live.isEmpty()) {
            kept.put(scope, live);
          }
        });
    return new NavigationOrder.Mode(kept, stored.version());
  }

  private NavigationFavourites resolveFavourites(
      FavouritesResponse stored, NavigationAccess access, List<String> gone) {
    List<NavigationFavourites.Item> member = new ArrayList<>();
    List<NavigationFavourites.Item> admin = new ArrayList<>();
    for (String key : stored.keys()) {
      Optional<NavigationEntry> entry = registry.starrable(key);
      NavigationAvailability availability = availability(entry, access);
      if (availability == NavigationAvailability.GONE) {
        gone.add(key);
      } else {
        (registry.isAdminEntry(key) ? admin : member)
            .add(
                new NavigationFavourites.Item(
                    entry.orElseThrow(), availability == NavigationAvailability.AVAILABLE));
      }
    }
    return new NavigationFavourites(true, stored.version(), stored.keys(), member, admin);
  }

  private NavigationHidden resolveHidden(
      HiddenResponse stored, NavigationAccess access, List<String> gone) {
    List<String> order = registry.entries().stream().map(NavigationEntry::key).toList();
    List<NavigationHidden.Item> member = new ArrayList<>();
    List<NavigationHidden.Item> admin = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    for (String key :
        stored.keys().stream().sorted(Comparator.comparingInt(order::indexOf)).toList()) {
      Optional<NavigationEntry> entry = registry.hideable(key);
      NavigationAvailability availability = availability(entry, access);
      if (availability == NavigationAvailability.GONE) {
        gone.add(key);
      } else {
        kept.add(key);
        (registry.isAdminEntry(key) ? admin : member)
            .add(
                new NavigationHidden.Item(
                    entry.orElseThrow(), availability == NavigationAvailability.AVAILABLE));
      }
    }
    return new NavigationHidden(true, stored.version(), kept, member, admin);
  }

  private NavigationAvailability availability(
      Optional<NavigationEntry> entry, NavigationAccess access) {
    return entry.map(e -> registry.availability(e, access)).orElse(NavigationAvailability.GONE);
  }

  /**
   * The outcome of {@link #resolve}.
   *
   * @param view the preferences to render
   * @param gone the stored keys the caller may no longer see
   */
  record Resolution(@NotNull NavigationPersonalisation view, @NotNull List<String> gone) {}
}
