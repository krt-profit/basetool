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

import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAvailability;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTab;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTabBar;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTabBar.Slot;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Fills the three configurable slots of a phone tab bar (REQ-UI-032).
 *
 * <p>A stored slot whose entry is available shows it. Every other position takes the first entry of
 * the pool that no slot shows yet: the mode's available favourites in order, then its default
 * slots. A position whose stored choice is only unavailable in the active context shows that entry
 * as a stand-in and takes the choice back on a context switch; a position with no stored choice is
 * automatic, which is every position of a bar that was never edited.
 */
@Component
@RequiredArgsConstructor
public class NavigationTabBarResolver {

  private final NavigationRegistry registry;

  /**
   * Resolves one mode's bar.
   *
   * @param mode the mode whose bar is meant
   * @param stored the stored bar, or {@link TabBarResponse#AUTOMATIC}
   * @param loaded whether the stored bar could be read
   * @param favourites the caller's favourites
   * @param access the caller's reachable roles and flags
   * @return the bar to render
   */
  @NotNull
  public NavigationTabBar resolve(
      @NotNull NavigationMode mode,
      @NotNull TabBarResponse stored,
      boolean loaded,
      @NotNull NavigationFavourites favourites,
      @NotNull NavigationAccess access) {
    List<Slot> slots = new ArrayList<>();
    Set<String> shown = new HashSet<>();
    for (int index = 0; index < TabBarResponse.SLOTS; index++) {
      Optional<NavigationEntry> chosen = available(mode, stored.slots().get(index), access);
      if (chosen.isPresent() && shown.add(chosen.get().key())) {
        slots.add(slot(index, chosen.get(), path(chosen.get()), false));
      }
    }
    List<Slot> pool = pool(mode, favourites, access);
    for (int index = 0; index < TabBarResponse.SLOTS; index++) {
      int position = index;
      if (slots.stream().anyMatch(slot -> slot.index() == position)) {
        continue;
      }
      pool.stream()
          .filter(candidate -> !shown.contains(candidate.entry().key()))
          .findFirst()
          .ifPresent(candidate -> {
            shown.add(candidate.entry().key());
            slots.add(
                slot(
                    position,
                    candidate.entry(),
                    candidate.path(),
                    stored.slots().get(position) != null));
          });
    }
    slots.sort(Comparator.comparingInt(Slot::index));
    return new NavigationTabBar(
        mode,
        loaded,
        stored.version(),
        stored.slots(),
        slots,
        favouriteChoices(mode, favourites),
        otherChoices(mode, favourites, access));
  }

  /**
   * The stored slot keys the caller may no longer have: an unknown key, a key of the other mode and
   * an entry a role condition refuses. An entry only the active context refuses stays.
   *
   * @param mode the mode whose bar is meant
   * @param stored the stored bar
   * @param access the caller's reachable roles and flags
   * @return the keys to delete
   */
  @NotNull
  public List<String> gone(
      @NotNull NavigationMode mode,
      @NotNull TabBarResponse stored,
      @NotNull NavigationAccess access) {
    return stored.slots().stream()
        .filter(Objects::nonNull)
        .filter(key ->
            entryOf(mode, key)
                .map(entry -> registry.availability(entry, access) == NavigationAvailability.GONE)
                .orElse(true))
        .toList();
  }

  private Optional<NavigationEntry> entryOf(NavigationMode mode, String key) {
    return registry.tabbable(mode).stream().filter(entry -> entry.key().equals(key)).findFirst();
  }

  private Optional<NavigationEntry> available(
      NavigationMode mode, String key, NavigationAccess access) {
    return key == null
        ? Optional.empty()
        : entryOf(mode, key)
            .filter(
                entry -> registry.availability(entry, access) == NavigationAvailability.AVAILABLE);
  }

  private List<Slot> pool(
      NavigationMode mode, NavigationFavourites favourites, NavigationAccess access) {
    List<Slot> pool = new ArrayList<>();
    favourites(mode, favourites).stream()
        .filter(NavigationFavourites.Item::available)
        .map(NavigationFavourites.Item::entry)
        .forEach(entry -> pool.add(slot(0, entry, path(entry), false)));
    registry.defaultTabs(mode).forEach(tab -> defaultSlot(tab, access).ifPresent(pool::add));
    return pool;
  }

  private Optional<Slot> defaultSlot(NavigationTab tab, NavigationAccess access) {
    List<NavigationEntry> candidates =
        tab.entryKeys().stream().flatMap(key -> registry.entry(key).stream()).toList();
    return candidates.stream()
        .filter(entry -> registry.permits(entry, access))
        .findFirst()
        .map(entry -> slot(0, entry, path(candidates.getFirst()), false));
  }

  private List<NavigationEntry> favouriteChoices(
      NavigationMode mode, NavigationFavourites favourites) {
    return favourites(mode, favourites).stream()
        .filter(NavigationFavourites.Item::available)
        .map(NavigationFavourites.Item::entry)
        .toList();
  }

  private List<NavigationEntry> otherChoices(
      NavigationMode mode, NavigationFavourites favourites, NavigationAccess access) {
    Set<String> starred =
        new HashSet<>(
            favouriteChoices(mode, favourites).stream().map(NavigationEntry::key).toList());
    return registry.tabbable(mode).stream()
        .filter(entry -> !starred.contains(entry.key()))
        .filter(entry -> registry.availability(entry, access) == NavigationAvailability.AVAILABLE)
        .toList();
  }

  private static List<NavigationFavourites.Item> favourites(
      NavigationMode mode, NavigationFavourites favourites) {
    return mode == NavigationMode.MEMBER ? favourites.member() : favourites.admin();
  }

  private Slot slot(int index, NavigationEntry entry, String path, boolean standIn) {
    return new Slot(index, entry, registry.iconOf(entry), path, standIn);
  }

  private static String path(NavigationEntry entry) {
    return entry.href().split("\\?", 2)[0];
  }
}
