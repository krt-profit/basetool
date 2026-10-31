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
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationGroup;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPageTabs;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTab;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The navigation one caller gets on one request: the {@link NavigationRegistry} with every entry,
 * group and tab their gates refuse left out.
 *
 * @param top the links above the member groups
 * @param main the permitted member groups that keep at least one entry
 * @param personal the personal pages
 * @param adminTop the admin hub link
 * @param admin the permitted administration groups that keep at least one entry
 * @param anonymous the links of a signed-out visitor
 * @param legal the legal links
 * @param fanKit the Fan Kit link
 * @param actions the quick access's create and booking actions
 * @param paletteTail the quick access's handbook and language actions
 * @param tabs the phone tab bar's slots that keep a permitted entry
 * @param pageTabs the permitted tabs of each merged page, by the key of each of its entries
 */
public record NavigationMenu(
    @NotNull Group top,
    @NotNull List<Group> main,
    @NotNull Group personal,
    @NotNull Group adminTop,
    @NotNull List<Group> admin,
    @NotNull Group anonymous,
    @NotNull Group legal,
    @NotNull Group fanKit,
    @NotNull Group actions,
    @NotNull Group paletteTail,
    @NotNull List<Tab> tabs,
    @NotNull Map<String, List<NavigationEntry>> pageTabs) {

  /**
   * Resolves the registry for one caller.
   *
   * @param registry the entry registry
   * @param access the caller's reachable roles and flags
   * @return the caller's navigation
   */
  @NotNull
  public static NavigationMenu resolve(
      @NotNull NavigationRegistry registry, @NotNull NavigationAccess access) {
    return new NavigationMenu(
        Group.of(registry.top(), access),
        groups(registry.main(), access),
        Group.of(registry.personal(), access),
        Group.of(registry.adminTop(), access),
        groups(registry.admin(), access),
        Group.of(registry.anonymous(), access),
        Group.of(registry.legal(), access),
        Group.of(registry.fanKit(), access),
        Group.of(registry.actions(), access),
        Group.of(registry.paletteTail(), access),
        registry.tabs().stream().flatMap(tab -> Tab.of(tab, registry, access).stream()).toList(),
        resolvePageTabs(registry, access));
  }

  /**
   * The tab bar of the page an entry belongs to, with the current tab marked.
   *
   * @param entryKey the key of any entry of the page's tab bar
   * @param requestUri the current request URI, with or without a query
   * @return the permitted tabs in order, or empty when the entry has no tab bar or fewer than two
   *     tabs are permitted
   */
  @NotNull
  public List<PageTab> pageTabs(@NotNull String entryKey, @NotNull String requestUri) {
    List<NavigationEntry> tabs = pageTabs.getOrDefault(entryKey, List.of());
    String path = requestUri.split("\\?", 2)[0];
    NavigationEntry current =
        tabs.stream()
            .filter(tab -> path.equals(tab.href()) || path.startsWith(tab.href() + "/"))
            .max(Comparator.comparingInt(tab -> tab.href().length()))
            .orElse(null);
    return tabs.stream().map(tab -> new PageTab(tab, tab == current)).toList();
  }

  /**
   * The quick access's own pages: every permitted quick-access-only entry of the member and then
   * the administration groups.
   *
   * @return the pages in group order
   */
  @NotNull
  public List<NavigationEntry> palettePages() {
    return Stream.concat(main.stream(), admin.stream())
        .flatMap(group -> group.palettePages().stream())
        .toList();
  }

  /**
   * The other paths of the area a menu link opens: the tabs of the merged page behind it.
   *
   * @param link a menu link
   * @return the space-separated paths of the permitted tabs other than the link's own, or {@code
   *     null} for a link without tabs
   */
  @Nullable
  public String areaPaths(@NotNull NavigationEntry link) {
    String paths =
        pageTabs.values().stream()
            .filter(tabs -> tabs.stream().anyMatch(tab -> tab.href().equals(link.href())))
            .findFirst()
            .stream()
            .flatMap(List::stream)
            .map(NavigationEntry::href)
            .filter(href -> !href.equals(link.href()))
            .distinct()
            .collect(Collectors.joining(" "));
    return paths.isEmpty() ? null : paths;
  }

  @NotNull
  private static Map<String, List<NavigationEntry>> resolvePageTabs(
      @NotNull NavigationRegistry registry, @NotNull NavigationAccess access) {
    Map<String, List<NavigationEntry>> byKey = new HashMap<>();
    for (NavigationPageTabs bar : registry.pageTabs()) {
      List<NavigationEntry> permitted =
          bar.entryKeys().stream()
              .flatMap(key -> registry.entry(key).stream())
              .filter(entry -> registry.permits(entry, access))
              .toList();
      if (permitted.size() >= 2) {
        bar.entryKeys().forEach(key -> byKey.put(key, permitted));
      }
    }
    return Map.copyOf(byKey);
  }

  @NotNull
  private static List<Group> groups(
      @NotNull List<NavigationGroup> groups, @NotNull NavigationAccess access) {
    return groups.stream()
        .map(group -> Group.of(group, access))
        .filter(group -> !group.links().isEmpty() || !group.palettePages().isEmpty())
        .toList();
  }

  /**
   * A group with the entries the caller is permitted, split into menu links and quick-access-only
   * pages.
   *
   * @param group the registry group
   * @param links the permitted menu links in default order
   * @param palettePages the permitted quick-access-only pages in default order
   */
  public record Group(
      @NotNull NavigationGroup group,
      @NotNull List<NavigationEntry> links,
      @NotNull List<NavigationEntry> palettePages) {

    @NotNull
    private static Group of(@NotNull NavigationGroup group, @NotNull NavigationAccess access) {
      List<NavigationEntry> permitted =
          group.entries().stream().filter(entry -> group.permits(entry, access)).toList();
      return new Group(
          group,
          permitted.stream().filter(entry -> !entry.paletteOnly()).toList(),
          permitted.stream().filter(NavigationEntry::paletteOnly).toList());
    }
  }

  /**
   * One tab of a merged page's tab bar.
   *
   * @param entry the tab's entry
   * @param current whether the tab is the page being shown
   */
  public record PageTab(@NotNull NavigationEntry entry, boolean current) {}

  /**
   * A phone tab with the entry it opens.
   *
   * @param tab the registry slot
   * @param entry the first permitted candidate entry
   * @param path the area the tab marks as current: the primary candidate's path
   */
  public record Tab(
      @NotNull NavigationTab tab, @NotNull NavigationEntry entry, @NotNull String path) {

    @NotNull
    private static Optional<Tab> of(
        @NotNull NavigationTab tab,
        @NotNull NavigationRegistry registry,
        @NotNull NavigationAccess access) {
      List<NavigationEntry> candidates =
          tab.entryKeys().stream().flatMap(key -> registry.entry(key).stream()).toList();
      if (candidates.isEmpty()) {
        return Optional.empty();
      }
      String path = candidates.getFirst().href();
      return candidates.stream()
          .filter(entry -> registry.permits(entry, access))
          .findFirst()
          .map(entry -> new Tab(tab, entry, path));
    }
  }
}
