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
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link NavigationMenu#resolve}: the registry's gates per caller. */
class NavigationMenuTest {

  private final NavigationRegistry registry = new NavigationRegistry();

  /** A member without the Job-Order area gets „Auftrag anlegen" in the menu and in the tab bar. */
  @Test
  void memberWithoutOrdersGetsTheCreateLink() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

    assertThat(keys(menu, "logistics"))
        .containsExactly(
            "hangar.overview",
            "storage.overview",
            "materialboerse.board",
            "refinery.orders",
            "orders.create");
    assertThat(menu.tabs().get(2).entry().key()).isEqualTo("orders.create");
    assertThat(menu.tabs().get(2).path()).isEqualTo("/orders");
  }

  /** The own-unit view stands in for the Job-Order area and its tab opens the orders page. */
  @Test
  void ownUnitViewStandsInForTheOrders() {
    NavigationMenu menu = resolve(Set.of(), Set.of(NavigationFlag.OWN_JOB_ORDERS));

    assertThat(keys(menu, "logistics")).contains("orders.own").doesNotContain("orders.create");
    assertThat(menu.tabs().get(2).entry().href()).isEqualTo("/orders");
  }

  /** A group gated on a role or flag the caller lacks is not offered at all. */
  @Test
  void gatedGroupsDisappear() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of());

    assertThat(menu.main())
        .extracting(group -> group.group().key())
        .containsExactly("missions", "logistics", "trade", "organisation", "resources");
    assertThat(menu.admin()).isEmpty();
  }

  /** Bank staff, promotion and both procurement surfaces add their groups in place. */
  @Test
  void enabledGroupsAppearInTheirPlace() {
    NavigationMenu menu =
        resolve(
            Set.of("BANK_EMPLOYEE"),
            Set.of(NavigationFlag.SUPPLY, NavigationFlag.PURCHASING, NavigationFlag.PROMOTION));

    assertThat(menu.main())
        .extracting(group -> group.group().key())
        .containsExactly(
            "missions",
            "logistics",
            "trade",
            "supply",
            "organisation",
            "promotion",
            "bank",
            "resources");
    assertThat(keys(menu, "bank")).doesNotContain("bank.grants");
  }

  /** Quick-access-only pages stay out of the menu links but feed the page index. */
  @Test
  void paletteOnlyPagesFeedOnlyTheIndex() {
    NavigationMenu menu = resolve(Set.of("ADMIN"), Set.of());

    assertThat(menu.palettePages())
        .extracting(NavigationEntry::key)
        .containsExactly(
            "hangar.unit",
            "storage.my",
            "storage.all",
            "admin.syncreports.uex",
            "admin.syncreports.scwiki");
    assertThat(keys(menu, "logistics")).doesNotContain("hangar.unit", "storage.my");
  }

  /** The language action offers only the language the page is not shown in. */
  @Test
  void offersOnlyTheOtherLanguage() {
    NavigationMenu menu = resolve(Set.of(), Set.of(NavigationFlag.LANGUAGE_EN));

    assertThat(menu.paletteTail().links())
        .extracting(NavigationEntry::key)
        .containsExactly("action.handbook", "action.language.de");
  }

  @NotNull
  private NavigationMenu resolve(@NotNull Set<String> roles, @NotNull Set<NavigationFlag> flags) {
    return NavigationMenu.resolve(registry, new NavigationAccess(roles, flags));
  }

  @NotNull
  private static List<String> keys(@NotNull NavigationMenu menu, @NotNull String groupKey) {
    return menu.main().stream()
        .filter(group -> group.group().key().equals(groupKey))
        .flatMap(group -> group.links().stream())
        .map(NavigationEntry::key)
        .toList();
  }
}
