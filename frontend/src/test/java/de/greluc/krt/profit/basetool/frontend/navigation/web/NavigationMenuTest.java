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

  /** A member without the Job-Order area gets „Auftrag anlegen" in the menu. */
  @Test
  void memberWithoutOrdersGetsTheCreateLink() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

    assertThat(keys(menu, "logistics"))
        .containsExactly("storage.overview", "refinery.orders", "orders.create");
  }

  /** The own-unit view stands in for the Job-Order area and its tab opens the orders page. */
  @Test
  void ownUnitViewStandsInForTheOrders() {
    NavigationMenu menu = resolve(Set.of(), Set.of(NavigationFlag.OWN_JOB_ORDERS));

    assertThat(keys(menu, "logistics")).contains("orders.own").doesNotContain("orders.create");
  }

  /** An assignee without any order list gets the „Aufträge" link and its tab opens the list. */
  @Test
  void assigneeWithoutProfitAccessGetsTheOrdersLink() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.ASSIGNED_JOB_ORDERS));

    assertThat(keys(menu, "logistics")).contains("orders.assigned", "orders.create");
  }

  /** A member who already reaches an order list gets no second „Aufträge" link for the entry. */
  @Test
  void anEntryAddsNoSecondOrdersLinkToAMemberWhoSeesTheQueueOrTheOwnList() {
    assertThat(
            keys(
                resolve(
                    Set.of(),
                    Set.of(NavigationFlag.JOB_ORDERS, NavigationFlag.ASSIGNED_JOB_ORDERS)),
                "logistics"))
        .contains("orders.list")
        .doesNotContain("orders.assigned");
    assertThat(
            keys(
                resolve(
                    Set.of(),
                    Set.of(NavigationFlag.OWN_JOB_ORDERS, NavigationFlag.ASSIGNED_JOB_ORDERS)),
                "logistics"))
        .contains("orders.own")
        .doesNotContain("orders.assigned");
  }

  /** Without an entry the link is absent. */
  @Test
  void withoutAnEntryThereIsNoAssignedLink() {
    assertThat(keys(resolve(Set.of("KRT_MEMBER"), Set.of()), "logistics"))
        .doesNotContain("orders.assigned");
  }

  /** A group gated on a role or flag the caller lacks is not offered at all. */
  @Test
  void gatedGroupsDisappear() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of());

    assertThat(menu.main())
        .extracting(group -> group.group().key())
        .containsExactly("operations", "logistics", "trade", "unit", "resources");
    assertThat(menu.admin()).isEmpty();
  }

  /** Bank staff add their group at its place; the resources group offers no menu link. */
  @Test
  void bankStaffAddTheirGroupInPlace() {
    NavigationMenu menu = resolve(Set.of("BANK_EMPLOYEE"), Set.of());

    assertThat(menu.main())
        .extracting(group -> group.group().key())
        .containsExactly("operations", "logistics", "trade", "unit", "bank", "resources");
    assertThat(keys(menu, "bank")).containsExactly("bank.dashboard");
    assertThat(keys(menu, "resources")).isEmpty();
    assertThat(menu.pageTabs("bank.dashboard", "/bank"))
        .extracting(tab -> tab.entry().key())
        .containsExactly("bank.dashboard", "bank.manage", "bank.requests");
  }

  /** Every group is open and a fully entitled member has 10 links, bank staff 11. */
  @Test
  void aFullyEntitledMemberHasTenLinksAndBankStaffEleven() {
    Set<NavigationFlag> flags =
        Set.of(
            NavigationFlag.JOB_ORDERS,
            NavigationFlag.SUPPLY,
            NavigationFlag.PURCHASING,
            NavigationFlag.PROMOTION,
            NavigationFlag.OPEN_LEITUNG,
            NavigationFlag.MANAGE_PROMOTIONS);

    NavigationMenu member = resolve(Set.of("KRT_MEMBER"), flags);
    NavigationMenu bank = resolve(Set.of("KRT_MEMBER", "BANK_EMPLOYEE"), flags);

    assertThat(links(member)).hasSize(10);
    assertThat(links(bank)).hasSize(11);
    assertThat(member.main()).noneMatch(group -> group.group().collapsed());
  }

  /** Supply and purchasing share the one „Beschaffung" link, supply first. */
  @Test
  void procurementIsOneLink() {
    assertThat(keys(resolve(Set.of(), Set.of(NavigationFlag.SUPPLY)), "trade"))
        .contains("supply.needs")
        .doesNotContain("purchasing.deals");
    assertThat(keys(resolve(Set.of(), Set.of(NavigationFlag.PURCHASING)), "trade"))
        .contains("purchasing.deals")
        .doesNotContain("supply.needs");
    assertThat(
            keys(
                resolve(Set.of(), Set.of(NavigationFlag.SUPPLY, NavigationFlag.PURCHASING)),
                "trade"))
        .contains("supply.needs")
        .doesNotContain("purchasing.deals");
    assertThat(keys(resolve(Set.of(), Set.of()), "trade"))
        .doesNotContain("supply.needs", "purchasing.deals");
  }

  /** The Leitung tab follows the capability flag, not a role. */
  @Test
  void leitungFollowsTheFlag() {
    NavigationMenu withoutFlag = resolve(Set.of("KRT_MEMBER", "OFFICER"), Set.of());
    NavigationMenu withFlag = resolve(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.OPEN_LEITUNG));

    assertThat(withoutFlag.pageTabs("orgchart.view", "/org-chart"))
        .extracting(tab -> tab.entry().key())
        .containsExactly("orgchart.view", "orgunitbank.overview");
    assertThat(withFlag.pageTabs("orgchart.view", "/org-chart"))
        .extracting(tab -> tab.entry().key())
        .containsExactly("orgchart.view", "orgunitbank.overview", "organisation.leadership");
  }

  /**
   * The promotion tabs follow the feature and the management flag; the topic and rank pages show
   * the Verwaltung tab as current, and a page left without the feature loses its link.
   */
  @Test
  void promotionManagementFollowsTheFlag() {
    NavigationMenu member = resolve(Set.of("OFFICER"), Set.of(NavigationFlag.PROMOTION));
    NavigationMenu manager =
        resolve(Set.of(), Set.of(NavigationFlag.PROMOTION, NavigationFlag.MANAGE_PROMOTIONS));

    assertThat(member.pageTabs("promotion.overview", "/promotion/overview"))
        .extracting(tab -> tab.entry().key())
        .containsExactly("promotion.overview", "promotion.evaluations");
    assertThat(manager.pageTabs("promotion.topics", "/promotion/admin/topics"))
        .extracting(tab -> tab.entry().key() + ":" + tab.current())
        .containsExactly(
            "promotion.overview:false", "promotion.evaluations:false", "promotion.manage:true");
    assertThat(manager.areaPaths(registry.entry("promotion.overview").orElseThrow()))
        .contains("/promotion/manage", "/promotion/admin/topics");
    assertThat(keys(resolve(Set.of("KRT_MEMBER"), Set.of()), "unit"))
        .doesNotContain("promotion.overview");
  }

  /** The storage page has exactly the two tabs; the storage option keeps the Lager tab current. */
  @Test
  void theStoragePageHasTwoTabsAndKeepsTheLagerTabForTheStorageOption() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of());

    assertThat(menu.pageTabs("storage.overview", "/inventory"))
        .extracting(tab -> tab.entry().key() + ":" + tab.current())
        .containsExactly("storage.overview:true", "storage.levels:false");
    assertThat(menu.pageTabs("storage.overview", "/inventory?storage=my"))
        .filteredOn(NavigationMenu.PageTab::current)
        .extracting(tab -> tab.entry().key())
        .containsExactly("storage.overview");
    assertThat(menu.pageTabs("storage.levels", "/inventory/stock-levels"))
        .filteredOn(NavigationMenu.PageTab::current)
        .extracting(tab -> tab.entry().key())
        .containsExactly("storage.levels");
  }

  /** The member pages that merged sibling pages show them as tabs under their own labels. */
  @Test
  void mergedMemberPagesHaveTheirTabs() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.JOB_ORDERS));

    assertThat(menu.pageTabs("missions.list", "/missions/new"))
        .extracting(tab -> tab.entry().key() + ":" + tab.current())
        .containsExactly("missions.list:true", "operations.list:false");
    assertThat(menu.pageTabs("hangar.catalog", "/ship-data"))
        .extracting(tab -> tab.entry().key() + ":" + tab.current())
        .containsExactly("hangar.overview:false", "hangar.catalog:true");
    assertThat(menu.pageTabs("orders.demand", "/orders/material-demand"))
        .extracting(tab -> tab.entry().key() + ":" + tab.current())
        .containsExactly("orders.list:false", "orders.demand:true");
    assertThat(menu.pageTabs("materials.profit", "/materials/profit-calculation"))
        .extracting(tab -> tab.entry().key())
        .containsExactly("materials.list", "materials.overview", "materials.profit");
    assertThat(menu.pageTabs("materials.list", "/materials").getFirst().entry().tabLabel())
        .isEqualTo("nav.tab.materialList");
    assertThat(menu.pageTabs("refinery.orders", "/refinery-orders")).isEmpty();
  }

  /** A menu link lights up on every tab path of its page. */
  @Test
  void aMenuLinkCarriesTheOtherPathsOfItsPage() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of());

    assertThat(menu.areaPaths(registry.entry("hangar.overview").orElseThrow()))
        .isEqualTo("/ship-data");
    assertThat(menu.areaPaths(registry.entry("storage.overview").orElseThrow()))
        .isEqualTo("/inventory/stock-levels");
    assertThat(menu.areaPaths(registry.entry("refinery.orders").orElseThrow())).isNull();
  }

  /** Quick-access-only pages stay out of the menu links but feed the page index. */
  @Test
  void paletteOnlyPagesFeedOnlyTheIndex() {
    NavigationMenu menu = resolve(Set.of("ADMIN"), Set.of());

    assertThat(menu.palettePages())
        .extracting(NavigationEntry::key)
        .contains(
            "operations.list",
            "hangar.catalog",
            "hangar.unit",
            "storage.levels",
            "storage.my",
            "storage.all",
            "sclinks.list",
            "admin.syncreports.uex",
            "admin.syncreports.scwiki");
    assertThat(keys(menu, "logistics")).doesNotContain("storage.levels", "storage.my");
    assertThat(keys(menu, "operations")).doesNotContain("operations.list", "hangar.unit");
  }

  /**
   * An administrator gets the hub, 13 links and the merged pages' tab bars with the current tab.
   */
  @Test
  void administratorGetsTheHubTheLinksAndTheTabBars() {
    NavigationMenu menu = resolve(Set.of("ADMIN"), Set.of());

    assertThat(menu.adminTop().links())
        .extracting(NavigationEntry::key)
        .containsExactly("admin.dashboard");
    assertThat(menu.admin().stream().mapToInt(group -> group.links().size()).sum()).isEqualTo(13);
    assertThat(menu.pageTabs("admin.terms", "/admin/terms"))
        .extracting(tab -> tab.entry().key() + ":" + tab.current())
        .containsExactly(
            "admin.members.list:false", "admin.discordregistrations:false", "admin.terms:true");
    assertThat(menu.pageTabs("admin.dangerzone", "/admin/settings/danger-zone?x=1"))
        .filteredOn(NavigationMenu.PageTab::current)
        .extracting(tab -> tab.entry().key())
        .containsExactly("admin.dangerzone");
    assertThat(menu.areaPaths(registry.entry("admin.privacy").orElseThrow()))
        .isEqualTo("/admin/deletion-requests");
    assertThat(menu.pageTabs("refinery.orders", "/refinery-orders")).isEmpty();
  }

  /** A member gets neither the hub, the admin links nor an admin tab bar. */
  @Test
  void memberGetsNoAdminHubOrTabs() {
    NavigationMenu menu = resolve(Set.of("KRT_MEMBER"), Set.of());

    assertThat(menu.adminTop().links()).isEmpty();
    assertThat(menu.pageTabs("admin.terms", "/admin/terms")).isEmpty();
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
  private static List<NavigationEntry> links(@NotNull NavigationMenu menu) {
    return menu.main().stream().flatMap(group -> group.links().stream()).toList();
  }

  /**
   * A hidden page leaves its group's links, is listed apart and takes its tabs out of the index.
   */
  @Test
  void aHiddenPageLeavesTheLinksAndItsTabsLeaveTheQuickAccess() {
    NavigationAccess access =
        new NavigationAccess(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

    NavigationMenu menu = NavigationMenu.resolve(registry, access, Set.of("missions.list"));

    assertThat(keys(menu, "operations")).containsExactly("hangar.overview");
    assertThat(group(menu, "operations").hiddenLinks())
        .extracting(NavigationEntry::key)
        .containsExactly("missions.list");
    assertThat(menu.palettePages())
        .extracting(NavigationEntry::key)
        .doesNotContain("operations.list");
    assertThat(menu.palettePages()).extracting(NavigationEntry::key).contains("hangar.catalog");
  }

  /** A hidden page still resolves as the current entry, so its star can show it again. */
  @Test
  void aHiddenPageStaysTheCurrentEntry() {
    NavigationAccess access =
        new NavigationAccess(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

    NavigationMenu menu = NavigationMenu.resolve(registry, access, Set.of("missions.list"));

    assertThat(menu.currentEntry("/missions")).map(NavigationEntry::key).contains("missions.list");
  }

  /** A group whose links are all hidden is kept so the edit mode can show it. */
  @Test
  void aGroupWithOnlyHiddenLinksIsKept() {
    NavigationAccess access =
        new NavigationAccess(Set.of("KRT_MEMBER"), Set.of(NavigationFlag.LANGUAGE_DE));

    NavigationMenu menu =
        NavigationMenu.resolve(registry, access, Set.of("missions.list", "hangar.overview"));

    assertThat(group(menu, "operations").links()).isEmpty();
    assertThat(group(menu, "operations").hiddenLinks()).hasSize(2);
  }

  private static NavigationMenu.Group group(NavigationMenu menu, String key) {
    return menu.main().stream()
        .filter(group -> group.group().key().equals(key))
        .findFirst()
        .orElseThrow();
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
