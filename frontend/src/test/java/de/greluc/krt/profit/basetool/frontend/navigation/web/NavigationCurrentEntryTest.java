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
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link NavigationMenu#currentEntry}: which entry the header star belongs to. */
class NavigationCurrentEntryTest {

  private final NavigationRegistry registry = new NavigationRegistry();

  private NavigationMenu menu(Set<String> roles, Set<NavigationFlag> flags) {
    return NavigationMenu.resolve(registry, new NavigationAccess(roles, flags));
  }

  private String current(NavigationMenu menu, String uri) {
    return menu.currentEntry(uri).map(NavigationEntry::key).orElse(null);
  }

  @Test
  @DisplayName("a page is found by its exact path, with or without a trailing slash or query")
  void findsAPageByItsPath() {
    NavigationMenu menu = menu(Set.of("KRT_MEMBER"), Set.of());

    assertThat(current(menu, "/missions")).isEqualTo("missions.list");
    assertThat(current(menu, "/missions/")).isEqualTo("missions.list");
    assertThat(current(menu, "/missions?page=2")).isEqualTo("missions.list");
  }

  @Test
  @DisplayName("a detail page is no entry and carries no star")
  void aDetailPageHasNoEntry() {
    NavigationMenu menu = menu(Set.of("KRT_MEMBER"), Set.of());

    assertThat(current(menu, "/missions/4711")).isNull();
    assertThat(current(menu, "/orders/4711")).isNull();
  }

  @Test
  @DisplayName("a tab of a merged page is its own entry")
  void aTabIsItsOwnEntry() {
    NavigationMenu menu = menu(Set.of("KRT_MEMBER"), Set.of());

    assertThat(current(menu, "/inventory/stock-levels")).isEqualTo("storage.levels");
    assertThat(current(menu, "/inventory")).isEqualTo("storage.overview");
  }

  @Test
  @DisplayName("an entry with a query matches only a request that carries it")
  void anEntryWithAQueryNeedsThatQuery() {
    NavigationMenu menu = menu(Set.of("KRT_MEMBER"), Set.of());

    assertThat(current(menu, "/inventory?storage=my")).isEqualTo("storage.my");
    assertThat(current(menu, "/inventory?storage=all&x=1")).isEqualTo("storage.all");
    assertThat(current(menu, "/inventory?storage=other")).isEqualTo("storage.overview");
  }

  @Test
  @DisplayName("an entry the caller may not open is no current entry")
  void anUnpermittedEntryIsNotCurrent() {
    assertThat(current(menu(Set.of("KRT_MEMBER"), Set.of()), "/admin/terms")).isNull();
    assertThat(current(menu(Set.of("ADMIN", "KRT_MEMBER"), Set.of()), "/admin/terms"))
        .isEqualTo("admin.terms");
  }

  @Test
  @DisplayName("the home page, the notifications and the quick-access actions are no favourites")
  void topAndActionsAreNotStarrable() {
    NavigationMenu menu = menu(Set.of("KRT_MEMBER"), Set.of());

    assertThat(current(menu, "/")).isNull();
    assertThat(current(menu, "/notifications")).isNull();
  }

  @Test
  @DisplayName("the registry classifies entries as available, context-only or gone")
  void classifiesAvailability() {
    NavigationAccess member = new NavigationAccess(Set.of("KRT_MEMBER"), Set.of());
    NavigationEntry orders = registry.starrable("orders.list").orElseThrow();
    NavigationEntry terms = registry.starrable("admin.terms").orElseThrow();
    NavigationEntry missions = registry.starrable("missions.list").orElseThrow();

    assertThat(registry.availability(missions, member).name()).isEqualTo("AVAILABLE");
    assertThat(registry.availability(orders, member).name()).isEqualTo("CONTEXT");
    assertThat(registry.availability(terms, member).name()).isEqualTo("GONE");
    assertThat(registry.starrable("home.dashboard")).isEmpty();
    assertThat(registry.starrable("action.handbook")).isEmpty();
    assertThat(registry.isAdminEntry("admin.terms")).isTrue();
    assertThat(registry.isAdminEntry("missions.list")).isFalse();
  }

  @Test
  @DisplayName(
      "only pages and tabs of the groups are starrable, not the links above them or actions")
  void marksOnlyGroupPagesAsStarrable() {
    NavigationMenu menu = menu(Set.of("KRT_MEMBER"), Set.of());

    assertThat(menu.starrable(registry.entry("missions.list").orElseThrow())).isTrue();
    assertThat(menu.starrable(registry.entry("storage.levels").orElseThrow())).isTrue();
    assertThat(menu.starrable(registry.entry("home.dashboard").orElseThrow())).isFalse();
    assertThat(menu.starrable(registry.entry("action.handbook").orElseThrow())).isFalse();
  }
}
