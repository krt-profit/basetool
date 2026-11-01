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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTabBar;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTabBar.Slot;
import de.greluc.krt.profit.basetool.frontend.navigation.model.TabBarResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link NavigationTabBarResolver}: filling, freezing, stand-ins and deletion. */
class NavigationTabBarResolverTest {

  private static final NavigationAccess MEMBER =
      new NavigationAccess(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

  private static final NavigationAccess ORDERS_QUEUE =
      new NavigationAccess(
          Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE, NavigationFlag.JOB_ORDERS));

  private static final NavigationAccess ADMIN =
      new NavigationAccess(Set.of("ADMIN", "KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

  private final NavigationRegistry registry = new NavigationRegistry();
  private final NavigationTabBarResolver resolver = new NavigationTabBarResolver(registry);

  private NavigationFavourites favourites(boolean[] available, String... keys) {
    List<NavigationFavourites.Item> member = new java.util.ArrayList<>();
    List<NavigationFavourites.Item> admin = new java.util.ArrayList<>();
    for (int index = 0; index < keys.length; index++) {
      NavigationEntry entry = registry.starrable(keys[index]).orElseThrow();
      (registry.isAdminEntry(keys[index]) ? admin : member)
          .add(new NavigationFavourites.Item(entry, available[index]));
    }
    return new NavigationFavourites(true, 1, List.of(keys), member, admin);
  }

  private NavigationFavourites favourites(String... keys) {
    boolean[] available = new boolean[keys.length];
    Arrays.fill(available, true);
    return favourites(available, keys);
  }

  private NavigationTabBar member(
      NavigationAccess access, NavigationFavourites favourites, String... stored) {
    return resolver.resolve(
        NavigationMode.MEMBER,
        new TabBarResponse(Arrays.asList(stored), 3),
        true,
        favourites,
        access);
  }

  private static List<String> shown(NavigationTabBar bar) {
    return bar.slots().stream().map(slot -> slot.entry().key()).toList();
  }

  @Test
  @DisplayName("an automatic bar without favourites shows the mode's default slots")
  void automaticWithoutFavouritesShowsTheDefaults() {
    NavigationTabBar bar = member(MEMBER, NavigationFavourites.NOT_LOADED, null, null, null);

    assertThat(bar.automatic()).isTrue();
    assertThat(shown(bar)).containsExactly("missions.list", "storage.overview", "orders.create");
    assertThat(bar.slots()).noneMatch(Slot::standIn);
  }

  @Test
  @DisplayName("an automatic bar takes the favourites in order, then the defaults, without repeats")
  void automaticFillsFromTheFavouritesThenTheDefaults() {
    NavigationTabBar bar =
        member(MEMBER, favourites("refinery.orders", "storage.overview"), null, null, null);

    assertThat(shown(bar)).containsExactly("refinery.orders", "storage.overview", "missions.list");
  }

  @Test
  @DisplayName("an unavailable favourite does not fill an automatic slot")
  void automaticSkipsAGreyedFavourite() {
    NavigationTabBar bar =
        member(
            MEMBER,
            favourites(new boolean[] {false, true}, "orders.list", "refinery.orders"),
            null,
            null,
            null);

    assertThat(shown(bar)).containsExactly("refinery.orders", "missions.list", "storage.overview");
  }

  @Test
  @DisplayName("a stored bar ignores the favourites and keeps its positions")
  void storedBarIgnoresTheFavourites() {
    NavigationTabBar bar =
        member(
            MEMBER,
            favourites("refinery.orders"),
            "storage.overview",
            "missions.list",
            "orders.create");

    assertThat(bar.automatic()).isFalse();
    assertThat(shown(bar)).containsExactly("storage.overview", "missions.list", "orders.create");
    assertThat(bar.slots()).noneMatch(Slot::standIn);
  }

  @Test
  @DisplayName("a slot unavailable in this context shows the next unshown favourite as a stand-in")
  void standInIsTheNextUnshownFavourite() {
    NavigationTabBar bar =
        member(
            MEMBER,
            favourites("refinery.orders"),
            "orders.list",
            "missions.list",
            "storage.overview");

    assertThat(shown(bar)).containsExactly("refinery.orders", "missions.list", "storage.overview");
    assertThat(bar.slots().get(0).standIn()).isTrue();
    assertThat(bar.slots().get(1).standIn()).isFalse();
    assertThat(bar.stored()).containsExactly("orders.list", "missions.list", "storage.overview");
  }

  @Test
  @DisplayName("without a favourite the stand-in is the next free default")
  void standInIsTheNextFreeDefault() {
    NavigationTabBar bar =
        member(
            MEMBER,
            NavigationFavourites.NOT_LOADED,
            "orders.list",
            "missions.list",
            "refinery.orders");

    assertThat(shown(bar)).containsExactly("storage.overview", "missions.list", "refinery.orders");
    assertThat(bar.slots().get(0).standIn()).isTrue();
  }

  @Test
  @DisplayName("the stored choice returns when the context offers it again")
  void theStoredChoiceReturnsWithTheContext() {
    NavigationTabBar bar =
        member(
            ORDERS_QUEUE,
            NavigationFavourites.NOT_LOADED,
            "orders.list",
            "missions.list",
            "storage.overview");

    assertThat(shown(bar)).containsExactly("orders.list", "missions.list", "storage.overview");
    assertThat(bar.slots()).noneMatch(Slot::standIn);
  }

  @Test
  @DisplayName("a deleted slot stays automatic and is no stand-in")
  void aClearedSlotIsFilledAutomatically() {
    NavigationTabBar bar =
        member(MEMBER, NavigationFavourites.NOT_LOADED, "storage.overview", null, "orders.create");

    assertThat(bar.automatic()).isFalse();
    assertThat(shown(bar)).containsExactly("storage.overview", "missions.list", "orders.create");
    assertThat(bar.slots().get(1).standIn()).isFalse();
  }

  @Test
  @DisplayName("an administrator's automatic bar is the admin default")
  void adminDefaultSlots() {
    NavigationTabBar bar =
        resolver.resolve(
            NavigationMode.ADMIN,
            TabBarResponse.AUTOMATIC,
            true,
            NavigationFavourites.NOT_LOADED,
            ADMIN);

    assertThat(shown(bar)).containsExactly("admin.dashboard", "admin.members", "admin.auditlog");
    assertThat(bar.slots()).extracting(Slot::icon).containsExactly("gear", "users", "gear");
  }

  @Test
  @DisplayName("the admin bar takes the admin favourites, not the member ones")
  void adminBarTakesTheAdminFavourites() {
    NavigationTabBar bar =
        resolver.resolve(
            NavigationMode.ADMIN,
            TabBarResponse.AUTOMATIC,
            true,
            favourites("missions.list", "admin.terms"),
            ADMIN);

    assertThat(shown(bar).get(0)).isEqualTo("admin.terms");
    assertThat(shown(bar)).doesNotContain("missions.list");
  }

  @Test
  @DisplayName("the pickers list the favourites first and every other available entry once")
  void choicesListTheFavouritesFirst() {
    NavigationTabBar bar = member(MEMBER, favourites("refinery.orders"), null, null, null);

    assertThat(bar.favouriteChoices())
        .extracting(NavigationEntry::key)
        .containsExactly("refinery.orders");
    assertThat(bar.otherChoices())
        .extracting(NavigationEntry::key)
        .contains("missions.list", "personal.profile")
        .doesNotContain("refinery.orders", "orders.list", "admin.terms");
  }

  @Test
  @DisplayName("a stored key of a lost role, of the other mode or unknown is to be deleted")
  void goneKeysAreDeleted() {
    TabBarResponse stored =
        new TabBarResponse(Arrays.asList("admin.terms", "future.page", "orders.list"), 1);

    assertThat(resolver.gone(NavigationMode.MEMBER, stored, MEMBER))
        .containsExactly("admin.terms", "future.page");
  }

  @Test
  @DisplayName("a stored slot only the active context refuses is kept")
  void contextOnlyGapIsNotDeleted() {
    TabBarResponse stored = new TabBarResponse(Arrays.asList("orders.list", null, null), 1);

    assertThat(resolver.gone(NavigationMode.MEMBER, stored, MEMBER)).isEmpty();
  }
}
