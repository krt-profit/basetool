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
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests of the stored order applied by {@link NavigationMenu} to the permitted groups and links.
 */
class NavigationMenuOrderTest {

  private static final NavigationRegistry REGISTRY = new NavigationRegistry();

  private static final NavigationAccess MEMBER =
      new NavigationAccess(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

  private static NavigationMenu menu(Set<String> hidden, NavigationOrder.Mode member) {
    return NavigationMenu.resolve(
        REGISTRY, MEMBER, hidden, new NavigationOrder(member, NavigationOrder.Mode.DEFAULT));
  }

  private static List<String> groupKeys(NavigationMenu menu) {
    return menu.main().stream().map(group -> group.group().key()).toList();
  }

  private static List<String> linkKeys(NavigationMenu menu, String groupKey) {
    return menu.main().stream()
        .filter(group -> group.group().key().equals(groupKey))
        .flatMap(group -> group.links().stream())
        .map(NavigationEntry::key)
        .toList();
  }

  @Test
  @DisplayName(
      "a stored group order moves the groups and keeps unknown groups after their predecessor")
  void ordersTheGroups() {
    NavigationMenu menu =
        menu(
            Set.of(),
            new NavigationOrder.Mode(Map.of("groups", List.of("logistics", "operations")), 1));

    assertThat(groupKeys(menu))
        .containsExactly("logistics", "trade", "unit", "resources", "operations");
  }

  @Test
  @DisplayName("a stored link order applies to the links of its group only")
  void ordersTheLinksOfOneGroup() {
    NavigationMenu menu =
        menu(
            Set.of(),
            new NavigationOrder.Mode(
                Map.of("operations", List.of("hangar.overview", "missions.list")), 1));

    assertThat(linkKeys(menu, "operations")).containsExactly("hangar.overview", "missions.list");
    assertThat(linkKeys(menu, "logistics"))
        .containsExactlyElementsOf(
            linkKeys(menu(Set.of(), NavigationOrder.Mode.DEFAULT), "logistics"));
  }

  @Test
  @DisplayName("a hidden page keeps no place among the links and returns to its stored one")
  void aHiddenPageLeavesTheOrderedLinks() {
    NavigationOrder.Mode order =
        new NavigationOrder.Mode(
            Map.of("operations", List.of("hangar.overview", "missions.list")), 1);

    assertThat(linkKeys(menu(Set.of("missions.list"), order), "operations"))
        .containsExactly("hangar.overview");
    assertThat(linkKeys(menu(Set.of(), order), "operations"))
        .containsExactly("hangar.overview", "missions.list");
  }

  @Test
  @DisplayName("stored keys of links the caller cannot see take no position")
  void positionsCountOnlyPermittedLinks() {
    NavigationMenu menu =
        menu(
            Set.of(),
            new NavigationOrder.Mode(
                Map.of("operations", List.of("bank.dashboard", "hangar.overview", "missions.list")),
                1));

    assertThat(linkKeys(menu, "operations")).containsExactly("hangar.overview", "missions.list");
  }

  @Test
  @DisplayName("a link the store does not know follows its default predecessor")
  void placesAnUnknownLinkAfterItsPredecessor() {
    List<String> defaults = linkKeys(menu(Set.of(), NavigationOrder.Mode.DEFAULT), "logistics");
    assertThat(defaults.size()).isGreaterThanOrEqualTo(3);
    String last = defaults.getLast();
    String first = defaults.getFirst();

    List<String> ordered =
        linkKeys(
            menu(Set.of(), new NavigationOrder.Mode(Map.of("logistics", List.of(last, first)), 1)),
            "logistics");

    assertThat(ordered.subList(0, 3)).containsExactly(last, first, defaults.get(1));
    assertThat(ordered).containsExactlyInAnyOrderElementsOf(defaults);
  }
}
