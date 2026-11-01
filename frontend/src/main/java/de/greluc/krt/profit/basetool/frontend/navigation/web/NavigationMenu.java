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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * @param pageTabs the permitted tab bar of each merged page, by the key of each of its tabs and of
 *     each entry they cover
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
    @NotNull Map<String, Bar> pageTabs) {

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
    return resolve(registry, access, Set.of());
  }

  /**
   * Resolves the registry for one caller who hid pages: a hidden page leaves its group's links and
   * is listed apart, and its tabs and views leave the quick access's pages.
   *
   * @param registry the entry registry
   * @param access the caller's reachable roles and flags
   * @param hiddenPages the keys of the pages the caller hid; keys that are no page are ignored
   * @return the caller's navigation
   */
  @NotNull
  public static NavigationMenu resolve(
      @NotNull NavigationRegistry registry,
      @NotNull NavigationAccess access,
      @NotNull Set<String> hiddenPages) {
    Set<String> hiddenEntries = registry.familyOf(hiddenPages);
    return new NavigationMenu(
        Group.of(registry.top(), access),
        groups(registry.main(), access, hiddenPages, hiddenEntries),
        Group.of(registry.personal(), access),
        Group.of(registry.adminTop(), access),
        groups(registry.admin(), access, hiddenPages, hiddenEntries),
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
   * @param entryKey the key of any entry of the page's tab bar, or of an entry a tab covers
   * @param requestUri the current request URI, with or without a query
   * @return the permitted tabs in order, or empty when the entry has no tab bar or fewer than two
   *     tabs are permitted
   */
  @NotNull
  public List<PageTab> pageTabs(@NotNull String entryKey, @NotNull String requestUri) {
    Bar bar = pageTabs.get(entryKey);
    if (bar == null) {
      return List.of();
    }
    String path = requestUri.split("\\?", 2)[0];
    NavigationEntry current =
        bar.tabs().stream()
            .filter(tab -> path.equals(tab.href()) || path.startsWith(tab.href() + "/"))
            .max(Comparator.comparingInt(tab -> tab.href().length()))
            .or(() ->
                bar.tabs().stream()
                    .filter(
                        tab -> tab.key().equals(bar.coveringTab().getOrDefault(entryKey, entryKey)))
                    .findFirst())
            .orElse(null);
    return bar.tabs().stream().map(tab -> new PageTab(tab, tab == current)).toList();
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
   * The page or tab a request shows, if it is one the caller can favourite: an entry whose path is
   * the request's exactly and whose query parameters the request carries. The entry with the most
   * query parameters wins; between equal ones the registry order decides.
   *
   * @param requestUri the current request URI, with or without a query
   * @return the entry, or empty for a detail page or any other URI that is no entry of the caller
   */
  @NotNull
  public Optional<NavigationEntry> currentEntry(@NotNull String requestUri) {
    String[] parts = requestUri.split("\\?", 2);
    String path = withoutTrailingSlash(parts[0]);
    Set<String> parameters = parts.length > 1 ? Set.of(parts[1].split("&")) : Set.of();
    return starrableEntries()
        .filter(entry -> showsRequest(entry.href(), path, parameters))
        .max(Comparator.comparingInt(entry -> entry.href().split("\\?", 2).length));
  }

  /**
   * Whether the caller can favourite the page or tab an entry opens.
   *
   * @param entry a menu or quick-access entry
   * @return {@code true} for a page or tab of a member or administration group and for a personal
   *     page, {@code false} for the links above the groups, legal links and actions
   */
  public boolean starrable(@NotNull NavigationEntry entry) {
    return starrableEntries().anyMatch(candidate -> candidate.key().equals(entry.key()));
  }

  @NotNull
  private Stream<NavigationEntry> starrableEntries() {
    return Stream.concat(
        Stream.concat(main.stream(), admin.stream())
            .flatMap(group ->
                Stream.of(group.links(), group.hiddenLinks(), group.palettePages())
                    .flatMap(List::stream)),
        personal.links().stream());
  }

  private static boolean showsRequest(
      @NotNull String href, @NotNull String path, @NotNull Set<String> parameters) {
    String[] parts = href.split("\\?", 2);
    return withoutTrailingSlash(parts[0]).equals(path)
        && (parts.length == 1 || parameters.containsAll(Set.of(parts[1].split("&"))));
  }

  @NotNull
  private static String withoutTrailingSlash(@NotNull String path) {
    return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  /**
   * The other paths of the area a menu link opens: the tabs of the merged page behind it.
   *
   * @param link a menu link
   * @return the space-separated paths of the permitted tabs and the entries they cover, other than
   *     the link's own, or {@code null} for a link without tabs
   */
  @Nullable
  public String areaPaths(@NotNull NavigationEntry link) {
    String paths =
        pageTabs.values().stream()
            .filter(bar -> bar.tabs().stream().anyMatch(tab -> tab.href().equals(link.href())))
            .findFirst()
            .stream()
            .flatMap(bar ->
                Stream.concat(
                    bar.tabs().stream().map(NavigationEntry::href), bar.coveredPaths().stream()))
            .filter(href -> !href.equals(link.href()))
            .distinct()
            .collect(Collectors.joining(" "));
    return paths.isEmpty() ? null : paths;
  }

  @NotNull
  private static Map<String, Bar> resolvePageTabs(
      @NotNull NavigationRegistry registry, @NotNull NavigationAccess access) {
    Map<String, Bar> byKey = new HashMap<>();
    for (NavigationPageTabs page : registry.pageTabs()) {
      List<NavigationEntry> permitted =
          page.entryKeys().stream()
              .flatMap(key -> registry.entry(key).stream())
              .filter(entry -> registry.permits(entry, access))
              .toList();
      if (permitted.size() < 2) {
        continue;
      }
      Map<String, String> coveringTab = new HashMap<>();
      List<String> coveredPaths = new ArrayList<>();
      page.covered()
          .forEach((tabKey, coveredKeys) ->
              coveredKeys.stream()
                  .flatMap(key -> registry.entry(key).stream())
                  .filter(entry -> registry.permits(entry, access))
                  .forEach(entry -> {
                    coveringTab.put(entry.key(), tabKey);
                    coveredPaths.add(entry.href());
                  }));
      Bar bar = new Bar(permitted, Map.copyOf(coveringTab), List.copyOf(coveredPaths));
      page.entryKeys().forEach(key -> byKey.put(key, bar));
      coveringTab.keySet().forEach(key -> byKey.put(key, bar));
    }
    return Map.copyOf(byKey);
  }

  @NotNull
  private static List<Group> groups(
      @NotNull List<NavigationGroup> groups,
      @NotNull NavigationAccess access,
      @NotNull Set<String> hiddenPages,
      @NotNull Set<String> hiddenEntries) {
    return groups.stream()
        .map(group -> Group.of(group, access, hiddenPages, hiddenEntries))
        .filter(group ->
            !group.links().isEmpty()
                || !group.hiddenLinks().isEmpty()
                || !group.palettePages().isEmpty())
        .toList();
  }

  /**
   * A group with the entries the caller is permitted, split into menu links, hidden menu links and
   * quick-access-only pages.
   *
   * @param group the registry group
   * @param links the permitted menu links the caller did not hide, in default order
   * @param hiddenLinks the permitted menu links the caller hid, in default order
   * @param palettePages the permitted quick-access-only pages of pages that are not hidden, in
   *     default order
   */
  public record Group(
      @NotNull NavigationGroup group,
      @NotNull List<NavigationEntry> links,
      @NotNull List<NavigationEntry> hiddenLinks,
      @NotNull List<NavigationEntry> palettePages) {

    @NotNull
    private static Group of(@NotNull NavigationGroup group, @NotNull NavigationAccess access) {
      return of(group, access, Set.of(), Set.of());
    }

    @NotNull
    private static Group of(
        @NotNull NavigationGroup group,
        @NotNull NavigationAccess access,
        @NotNull Set<String> hiddenPages,
        @NotNull Set<String> hiddenEntries) {
      List<NavigationEntry> permitted =
          group.entries().stream().filter(entry -> group.permits(entry, access)).toList();
      List<NavigationEntry> menu =
          permitted.stream().filter(entry -> !entry.paletteOnly()).toList();
      return new Group(
          group,
          menu.stream().filter(entry -> !hiddenPages.contains(entry.key())).toList(),
          menu.stream().filter(entry -> hiddenPages.contains(entry.key())).toList(),
          permitted.stream()
              .filter(NavigationEntry::paletteOnly)
              .filter(entry -> !hiddenEntries.contains(entry.key()))
              .toList());
    }
  }

  /**
   * The permitted part of one merged page's tab bar.
   *
   * @param tabs the permitted tabs in order
   * @param coveringTab the key of the covering tab, by the key of each permitted covered entry
   * @param coveredPaths the paths of the permitted covered entries
   */
  public record Bar(
      @NotNull List<NavigationEntry> tabs,
      @NotNull Map<String, String> coveringTab,
      @NotNull List<String> coveredPaths) {}

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
