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

    assertThat(keys).doesNotHaveDuplicates().contains("missions", "personal", "admin-operations");
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

  /** A key lookup finds the entry with its path; an unknown key finds nothing. */
  @Test
  void looksEntriesUpByKey() {
    assertThat(registry.entry("missions.list")).map(NavigationEntry::href).contains("/missions");
    assertThat(registry.entry("storage.my")).map(NavigationEntry::href).contains("/inventory/my");
    assertThat(registry.entry("nope.nope")).isEmpty();
  }
}
