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

import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationGroup;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTab;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link NavigationRegistry}: stable, well-formed and unique entry keys. */
class NavigationRegistryTest {

  private final NavigationRegistry registry = new NavigationRegistry();

  /** Every entry key follows the documented format: lower-case dot-separated segments. */
  @Test
  void everyKeyFollowsTheFormat() {
    assertThat(registry.entries())
        .isNotEmpty()
        .allSatisfy(
            entry -> assertThat(NavigationEntry.isValidKey(entry.key())).as(entry.key()).isTrue());
  }

  /** The format refuses upper case, hyphens, a single segment, empty segments and long keys. */
  @Test
  void theFormatRefusesMalformedKeys() {
    assertThat(NavigationEntry.isValidKey("missions.list")).isTrue();
    assertThat(NavigationEntry.isValidKey("admin.syncreports.uex")).isTrue();
    assertThat(NavigationEntry.isValidKey("missions")).isFalse();
    assertThat(NavigationEntry.isValidKey("Missions.list")).isFalse();
    assertThat(NavigationEntry.isValidKey("missions-list.x")).isFalse();
    assertThat(NavigationEntry.isValidKey("missions..list")).isFalse();
    assertThat(NavigationEntry.isValidKey("1missions.list")).isFalse();
    assertThat(NavigationEntry.isValidKey("a." + "b".repeat(63))).isFalse();
  }

  /** No two entries share a key, so a stored key names exactly one entry. */
  @Test
  void keysAreUnique() {
    List<String> keys = registry.entries().stream().map(NavigationEntry::key).toList();

    assertThat(keys).doesNotHaveDuplicates();
  }

  /** Every group key is unique as well, since the drawer remembers groups by it. */
  @Test
  void groupKeysAreUnique() {
    List<String> keys = registry.groups().stream().map(NavigationGroup::key).toList();

    assertThat(keys).doesNotHaveDuplicates().contains("operations", "personal", "admin-operations");
  }

  /** The phone tab bar holds today's four tabs, each naming registered entries. */
  @Test
  void theTabBarNamesRegisteredEntries() {
    assertThat(registry.tabs())
        .extracting(NavigationTab::testId)
        .containsExactly(
            "mobile-tab-missions",
            "mobile-tab-inventory",
            "mobile-tab-orders",
            "mobile-tab-notifications");
    assertThat(registry.tabs())
        .flatExtracting(NavigationTab::entryKeys)
        .allSatisfy(key -> assertThat(registry.entry(key)).as(key).isPresent());
    assertThat(registry.tabs().get(2).entryKeys())
        .containsExactly("orders.list", "orders.own", "orders.assigned", "orders.create");
  }

  /** Admin mode has the hub and exactly 13 menu links in three groups that start collapsed. */
  @Test
  void adminModeHasThirteenLinksInThreeCollapsedGroups() {
    assertThat(registry.admin())
        .extracting(NavigationGroup::key)
        .containsExactly("admin-members", "admin-masterdata", "admin-operations");
    assertThat(registry.admin()).allSatisfy(group -> assertThat(group.collapsed()).isTrue());
    assertThat(registry.admin().stream().flatMap(group -> group.entries().stream()))
        .filteredOn(entry -> !entry.paletteOnly())
        .hasSize(13);
    assertThat(registry.adminTop().entries())
        .extracting(NavigationEntry::href)
        .containsExactly("/admin");
  }

  /** Every tab bar names registered, quick-access-only or linked entries, and no key twice. */
  @Test
  void pageTabsNameRegisteredEntries() {
    assertThat(registry.pageTabs())
        .flatExtracting(tabs -> tabs.entryKeys())
        .doesNotHaveDuplicates()
        .allSatisfy(key -> assertThat(registry.entry(key)).as(key).isPresent());
  }

  /** Covered entries of a tab are registered entries. */
  @Test
  void coveredEntriesAreRegistered() {
    List<String> covered =
        registry.pageTabs().stream()
            .flatMap(tabs -> tabs.covered().values().stream())
            .flatMap(List::stream)
            .toList();

    assertThat(covered)
        .isNotEmpty()
        .allSatisfy(key -> assertThat(registry.entry(key)).as(key).isPresent());
  }

  /** The member default is four task groups plus bank and the quick-access-only resources. */
  @Test
  void theMemberDefaultIsFourTaskGroupsAndBank() {
    assertThat(registry.main())
        .extracting(NavigationGroup::key)
        .containsExactly("operations", "logistics", "trade", "unit", "bank", "resources");
    assertThat(registry.main().stream().flatMap(group -> group.entries().stream()))
        .filteredOn(entry -> !entry.paletteOnly())
        .extracting(NavigationEntry::href)
        .containsExactlyInAnyOrder(
            "/missions",
            "/hangar",
            "/inventory",
            "/refinery-orders",
            "/orders",
            "/orders",
            "/orders",
            "/orders/create",
            "/materials",
            "/materialboerse",
            "/supply/needs",
            "/purchasing/deals",
            "/org-chart",
            "/promotion/overview",
            "/bank");
  }

  /** Every URL the former member drawer linked still opens a registered entry's page. */
  @Test
  void everyFormerMemberUrlStillHasAnEntry() {
    List<String> former =
        List.of(
            "/missions",
            "/operations",
            "/hangar",
            "/hangar/squadron",
            "/ship-data",
            "/inventory",
            "/inventory?storage=my",
            "/inventory?storage=all",
            "/inventory/stock-levels",
            "/materialboerse",
            "/refinery-orders",
            "/orders",
            "/orders/material-demand",
            "/blueprint-overview",
            "/materials",
            "/materials/overview",
            "/materials/profit-calculation",
            "/supply/needs",
            "/supply/donations/mine",
            "/purchasing/deals",
            "/org-chart",
            "/organisation/leitung",
            "/org-unit-bank",
            "/promotion/overview",
            "/promotion/my-evaluations",
            "/promotion/manage",
            "/promotion/admin/topics",
            "/promotion/admin/rank-requirements",
            "/bank",
            "/bank/manage",
            "/bank/grants",
            "/bank/requests",
            "/sc-links",
            "/personal-inventory",
            "/personal-inventory/blueprints",
            "/profile",
            "/connected-apps",
            "/notifications");

    assertThat(registry.entries()).extracting(NavigationEntry::href).containsAll(former);
  }

  /** A key lookup finds the entry with its path; an unknown key finds nothing. */
  @Test
  void looksEntriesUpByKey() {
    assertThat(registry.entry("missions.list")).map(NavigationEntry::href).contains("/missions");
    assertThat(registry.entry("storage.my"))
        .map(NavigationEntry::href)
        .contains("/inventory?storage=my");
    assertThat(registry.entry("nope.nope")).isEmpty();
  }

  /** Only menu links of a member or administration group can be hidden. */
  @Test
  void onlyMenuLinksAreHideable() {
    assertThat(registry.hideable("missions.list")).isPresent();
    assertThat(registry.hideable("admin.members")).isPresent();
    assertThat(registry.hideable("operations.list")).as("a tab").isEmpty();
    assertThat(registry.hideable("storage.my")).as("a view").isEmpty();
    assertThat(registry.hideable("personal.profile")).as("a personal page").isEmpty();
    assertThat(registry.hideable("home.dashboard")).as("home").isEmpty();
    assertThat(registry.hideable("future.page")).isEmpty();
  }

  /** A hidden page takes its tab bar and its views along; pages of other bars stay. */
  @Test
  void aHiddenPageTakesItsTabsAndViewsAlong() {
    assertThat(registry.familyOf(Set.of("missions.list")))
        .containsExactlyInAnyOrder("missions.list", "operations.list");
    assertThat(registry.familyOf(Set.of("storage.overview")))
        .containsExactlyInAnyOrder(
            "storage.overview", "storage.levels", "storage.my", "storage.all");
    assertThat(registry.familyOf(Set.of("hangar.overview")))
        .containsExactlyInAnyOrder("hangar.overview", "hangar.catalog", "hangar.unit");
    assertThat(registry.familyOf(Set.of("bank.dashboard")))
        .contains("bank.manage", "bank.grants", "bank.requests");
    assertThat(registry.familyOf(Set.of("orders.own"))).contains("orders.demand");
    assertThat(registry.familyOf(Set.of("future.page"))).isEmpty();
  }

  /** An administration page and a member page that open the same path do not share a family. */
  @Test
  void theAdministrationAndTheMemberZoneKeepApart() {
    assertThat(registry.familyOf(Set.of("admin.shipdata"))).containsExactly("admin.shipdata");
    assertThat(registry.familyOf(Set.of("admin.dataimports")))
        .contains("admin.p4kimport", "admin.syncreports", "admin.syncreports.uex");
  }
}
