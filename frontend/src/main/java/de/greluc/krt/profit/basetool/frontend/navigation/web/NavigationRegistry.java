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

import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry.of;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.ASSIGNED_JOB_ORDERS;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.BLUEPRINT_OVERVIEW;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.JOB_ORDERS;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.LANGUAGE_DE;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.LANGUAGE_EN;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.LOGISTICIAN_HERE;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.MANAGE_PROMOTIONS;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.OPEN_LEITUNG;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.OWN_JOB_ORDERS;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.PROMOTION;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.PURCHASING;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag.SUPPLY;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTrait.EXTERNAL;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTrait.LABEL_WITH_ICON;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTrait.NOT_INDEXED;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTrait.PALETTE_ONLY;
import static de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTrait.UNREAD_BADGE;

import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAvailability;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationGate;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationGroup;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPageTabs;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTab;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The one frontend registry of navigation entries (REQ-UI-026): the drawer, the phone tab bar and
 * the quick access's own pages and actions are rendered from it, every link under its stable entry
 * key (format: {@link NavigationEntry}).
 *
 * <p>The zones keep their default order here; a later reorganisation edits this registry, not the
 * templates.
 */
@Component
@Getter
@Accessors(fluent = true)
public class NavigationRegistry {

  private static final String ICON_PLUS = "plus";

  private static final String ICON_BOOKOUT = "bookout";

  private static final String ICON_USERS = "users";

  private static final String ICON_BANK = "bank";

  private static final String ICON_WEDGE = "wedge";

  private static final String ICON_CRATE = "crate";

  private static final String ICON_SWAP = "swap";

  private static final String ICON_GEAR = "gear";

  private static final String ICON_DATABASE = "database";

  private static final String ICON_LIST = "list";

  private static final String ICON_EXTERNAL = "external-link";

  private static final String ICON_USER = "user";

  private static final String HANDBOOK_URL = "https://github.com/krt-profit/basetool/wiki";

  /** The quick-access-only views that open from a page without being a tab of its tab bar. */
  private static final Map<String, List<String>> PAGE_VIEWS =
      Map.of(
          "hangar.overview", List.of("hangar.unit"),
          "storage.overview", List.of("storage.my", "storage.all"),
          "admin.dataimports", List.of("admin.syncreports.uex", "admin.syncreports.scwiki"));

  private static final NavigationGate LEITUNG_GATE = NavigationGate.requires(OPEN_LEITUNG);

  private static final NavigationGate PROMOTION_MANAGEMENT_GATE =
      NavigationGate.requires(MANAGE_PROMOTIONS);

  /** The two links above the member groups: home and notifications. */
  private final NavigationGroup top = buildTop();

  /** The member groups of the drawer, in their default order. */
  private final List<NavigationGroup> main = buildMain();

  /** The personal pages of the user row's panel, also searchable by the menu filter. */
  private final NavigationGroup personal = buildPersonal();

  /** The admin hub link above the administration groups. */
  private final NavigationGroup adminTop = buildAdminTop();

  /** The administration groups, shown in admin mode only. */
  private final List<NavigationGroup> admin = buildAdmin();

  /** The tab bars of the pages that merge several pages. */
  private final List<NavigationPageTabs> pageTabs = buildPageTabs();

  /** The links a signed-out visitor gets. */
  private final NavigationGroup anonymous = buildAnonymous();

  /** The legal links and the handbook at the end of the drawer list. */
  private final NavigationGroup legal = buildLegal();

  /** The Fan Kit link after the fan-site notice. */
  private final NavigationGroup fanKit = buildFanKit();

  /** The quick access's create and booking actions. */
  private final NavigationGroup actions = buildActions();

  /** The quick access's handbook and language actions. */
  private final NavigationGroup paletteTail = buildPaletteTail();

  /** The member mode's default phone tab-bar slots (REQ-UI-026). */
  private final List<NavigationTab> tabs = buildTabs();

  /** The administration mode's default phone tab-bar slots (REQ-UI-026). */
  private final List<NavigationTab> adminTabs = buildAdminTabs();

  @Getter(AccessLevel.NONE)
  private final Map<String, NavigationEntry> byKey =
      entries().stream()
          .collect(Collectors.toUnmodifiableMap(NavigationEntry::key, Function.identity()));

  @Getter(AccessLevel.NONE)
  private final Map<String, NavigationGroup> groupByKey =
      groups().stream()
          .flatMap(group -> group.entries().stream().map(entry -> Map.entry(entry.key(), group)))
          .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

  @Getter(AccessLevel.NONE)
  private final Set<String> adminKeys = keysOf(admin);

  @Getter(AccessLevel.NONE)
  private final Set<String> starrableKeys =
      Stream.concat(
              keysOf(main).stream(),
              Stream.concat(keysOf(List.of(personal)).stream(), adminKeys.stream()))
          .collect(Collectors.toUnmodifiableSet());

  @Getter(AccessLevel.NONE)
  private final Set<String> hideableKeys =
      Stream.concat(main.stream(), admin.stream())
          .flatMap(group -> group.entries().stream())
          .filter(entry -> !entry.paletteOnly())
          .map(NavigationEntry::key)
          .collect(Collectors.toUnmodifiableSet());

  @Getter(AccessLevel.NONE)
  private final Map<String, Set<String>> families = buildFamilies();

  @Getter(AccessLevel.NONE)
  private final Map<NavigationMode, List<NavigationEntry>> tabbable = buildTabbable();

  @NotNull
  private static Set<String> keysOf(@NotNull List<NavigationGroup> groups) {
    return groups.stream()
        .flatMap(group -> group.entries().stream())
        .map(NavigationEntry::key)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Every group of the registry, each once.
   *
   * @return the groups in zone order
   */
  @NotNull
  public List<NavigationGroup> groups() {
    return Stream.of(
            Stream.of(top),
            main.stream(),
            Stream.of(personal),
            Stream.of(adminTop),
            admin.stream(),
            Stream.of(anonymous, legal, fanKit, actions, paletteTail))
        .flatMap(Function.identity())
        .toList();
  }

  /**
   * Every entry of the registry, each once.
   *
   * @return the entries in zone order
   */
  @NotNull
  public List<NavigationEntry> entries() {
    return groups().stream().flatMap(group -> group.entries().stream()).toList();
  }

  /**
   * Looks an entry up by its key.
   *
   * @param key the entry key
   * @return the entry, or empty for an unknown key
   */
  @NotNull
  public Optional<NavigationEntry> entry(@NotNull String key) {
    return Optional.ofNullable(byKey.get(key));
  }

  /**
   * Whether a caller passes both an entry's own gate and its group's.
   *
   * @param entry a registered entry
   * @param access the caller's reachable roles and flags
   * @return {@code true} iff the entry renders for the caller
   */
  public boolean permits(@NotNull NavigationEntry entry, @NotNull NavigationAccess access) {
    NavigationGroup group = groupByKey.get(entry.key());
    return group != null && group.permits(entry, access);
  }

  /**
   * Looks up an entry a member can favourite: a page or tab of a member group, the personal pages
   * or an administration group.
   *
   * @param key the entry key
   * @return the entry, or empty for an unknown key or an entry that cannot be a favourite
   */
  @NotNull
  public Optional<NavigationEntry> starrable(@NotNull String key) {
    return entry(key).filter(_ -> starrableKeys.contains(key));
  }

  /**
   * Looks up a page a member can hide: a menu link of a member or administration group. Tabs,
   * quick-access-only entries and the personal pages cannot be hidden.
   *
   * @param key the entry key
   * @return the entry, or empty for an unknown key or an entry that cannot be hidden
   */
  @NotNull
  public Optional<NavigationEntry> hideable(@NotNull String key) {
    return entry(key).filter(_ -> hideableKeys.contains(key));
  }

  /**
   * The entries that leave the quick access with the given hidden pages: the pages themselves, the
   * tabs of their tab bars and the views they open.
   *
   * @param hiddenPages the keys of the hidden pages; keys that are no page are ignored
   * @return the keys of every entry the hidden pages take with them
   */
  @NotNull
  public Set<String> familyOf(@NotNull Collection<String> hiddenPages) {
    return hiddenPages.stream()
        .flatMap(page -> families.getOrDefault(page, Set.of()).stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * The entries a slot of the phone tab bar of one mode can hold, in registry order: every page and
   * tab of the mode, the personal pages and the administration hub included.
   *
   * @param mode the mode whose bar is meant
   * @return the entries a slot can hold
   */
  @NotNull
  public List<NavigationEntry> tabbable(@NotNull NavigationMode mode) {
    return tabbable.get(mode);
  }

  /**
   * The default slots of one mode's phone tab bar.
   *
   * @param mode the mode whose bar is meant
   * @return the default slots in order
   */
  @NotNull
  public List<NavigationTab> defaultTabs(@NotNull NavigationMode mode) {
    return mode == NavigationMode.MEMBER ? tabs : adminTabs;
  }

  /**
   * The sprite icon an entry shows as a tab: its own, else its group's.
   *
   * @param entry a registered entry
   * @return the icon name
   */
  @NotNull
  public String iconOf(@NotNull NavigationEntry entry) {
    if (entry.icon() != null) {
      return entry.icon();
    }
    NavigationGroup group = groupByKey.get(entry.key());
    return group != null && group.icon() != null ? group.icon() : ICON_LIST;
  }

  /**
   * Whether a starrable entry belongs to the administration mode.
   *
   * @param key the entry key
   * @return {@code true} iff the key is an entry of an administration group
   */
  public boolean isAdminEntry(@NotNull String key) {
    return adminKeys.contains(key);
  }

  /**
   * Tells whether an entry is open to a caller, or why not.
   *
   * @param entry a registered entry
   * @param access the caller's reachable roles and flags
   * @return {@link NavigationAvailability#AVAILABLE} when the gates pass, {@link
   *     NavigationAvailability#CONTEXT} when only a per-request flag refuses, {@link
   *     NavigationAvailability#GONE} when a role condition fails
   */
  @NotNull
  public NavigationAvailability availability(
      @NotNull NavigationEntry entry, @NotNull NavigationAccess access) {
    NavigationGroup group = groupByKey.get(entry.key());
    if (group == null) {
      return NavigationAvailability.GONE;
    }
    if (group.permits(entry, access)) {
      return NavigationAvailability.AVAILABLE;
    }
    return group.gate().permitsRoles(access) && entry.gate().permitsRoles(access)
        ? NavigationAvailability.CONTEXT
        : NavigationAvailability.GONE;
  }

  /**
   * Groups the entries that belong to one hideable page: a tab bar belongs to the menu links of its
   * zone that open one of its tabs; a view that has no tab of its own is listed in {@link
   * #PAGE_VIEWS}.
   */
  @NotNull
  private Map<NavigationMode, List<NavigationEntry>> buildTabbable() {
    return Map.of(
        NavigationMode.MEMBER,
        Stream.concat(main.stream(), Stream.of(personal))
            .flatMap(group -> group.entries().stream())
            .toList(),
        NavigationMode.ADMIN,
        Stream.concat(Stream.of(adminTop), admin.stream())
            .flatMap(group -> group.entries().stream())
            .toList());
  }

  @NotNull
  private Map<String, Set<String>> buildFamilies() {
    Map<String, Set<String>> byPage = new HashMap<>();
    List<NavigationEntry> menuLinks =
        Stream.concat(main.stream(), admin.stream())
            .flatMap(group -> group.entries().stream())
            .filter(entry -> !entry.paletteOnly())
            .toList();
    menuLinks.forEach(link -> byPage.put(link.key(), new LinkedHashSet<>(Set.of(link.key()))));
    for (NavigationPageTabs bar : pageTabs) {
      boolean adminBar = adminKeys.contains(bar.entryKeys().getFirst());
      Set<String> barKeys = new LinkedHashSet<>(bar.entryKeys());
      bar.covered().values().forEach(barKeys::addAll);
      Set<String> tabHrefs =
          bar.entryKeys().stream()
              .flatMap(key -> entry(key).stream())
              .map(NavigationEntry::href)
              .collect(Collectors.toSet());
      menuLinks.stream()
          .filter(link -> adminKeys.contains(link.key()) == adminBar)
          .filter(link -> tabHrefs.contains(link.href()))
          .forEach(link -> byPage.get(link.key()).addAll(barKeys));
    }
    PAGE_VIEWS.forEach((page, views) -> byPage.get(page).addAll(views));
    return byPage.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
  }

  @NotNull
  private static NavigationGroup buildTop() {
    return NavigationGroup.of(
        "general",
        "nav.group.general",
        null,
        of("home.dashboard", "/", "nav.home").keywords("palette.keywords.home").icon(ICON_LIST),
        of("notifications.list", "/notifications", "nav.notifications")
            .keywords("palette.keywords.notifications")
            .testId("nav-notifications")
            .icon("bell")
            .with(UNREAD_BADGE));
  }

  @NotNull
  private static List<NavigationGroup> buildMain() {
    return List.of(
        buildOperations(),
        buildLogistics(),
        buildTrade(),
        buildUnit(),
        buildBank(),
        buildResources());
  }

  @NotNull
  private static NavigationGroup buildOperations() {
    return NavigationGroup.of(
        "operations",
        "nav.group.operations",
        ICON_WEDGE,
        of("missions.list", "/missions", "nav.page.missions")
            .keywords("palette.keywords.missions")
            .testId("nav-missions"),
        of("operations.list", "/operations", "nav.page.operations")
            .keywords("palette.keywords.operations")
            .with(PALETTE_ONLY),
        of("hangar.overview", "/hangar", "nav.hangar")
            .keywords("palette.keywords.hangar")
            .testId("nav-hangar"),
        of("hangar.catalog", "/ship-data", "nav.page.shipCatalog")
            .keywords("palette.keywords.shipCatalog")
            .with(PALETTE_ONLY),
        of("hangar.unit", "/hangar/squadron", "palette.page.hangarUnit")
            .keywords("palette.keywords.hangarUnit")
            .with(PALETTE_ONLY));
  }

  @NotNull
  private static NavigationGroup buildLogistics() {
    return NavigationGroup.of(
        "logistics",
        "nav.group.logistics",
        ICON_CRATE,
        of("storage.overview", "/inventory", "nav.page.storage")
            .keywords("palette.keywords.inventory")
            .testId("nav-storage"),
        of("storage.levels", "/inventory/stock-levels", "inventory.stockLevels.tab")
            .keywords("palette.keywords.stockLevels")
            .with(PALETTE_ONLY),
        of("storage.my", "/inventory?storage=my", "palette.page.inventoryMy")
            .keywords("palette.keywords.inventoryMy")
            .with(PALETTE_ONLY),
        of("storage.all", "/inventory?storage=all", "palette.page.inventoryAll")
            .keywords("palette.keywords.inventoryAll")
            .with(PALETTE_ONLY),
        of("refinery.orders", "/refinery-orders", "nav.page.refinery")
            .keywords("palette.keywords.refinery")
            .testId("nav-refinery"),
        of("orders.list", "/orders", "nav.page.orders")
            .keywords("palette.keywords.orders")
            .gate(NavigationGate.requires(JOB_ORDERS))
            .testId("nav-orders"),
        of("orders.own", "/orders", "nav.page.orders")
            .keywords("palette.keywords.orders")
            .gate(NavigationGate.requires(OWN_JOB_ORDERS).andExcludes(JOB_ORDERS))
            .testId("nav-orders-own"),
        of("orders.assigned", "/orders", "nav.page.orders")
            .keywords("palette.keywords.orders")
            .gate(
                NavigationGate.requires(ASSIGNED_JOB_ORDERS)
                    .andExcludes(JOB_ORDERS, OWN_JOB_ORDERS))
            .testId("nav-orders-assigned"),
        of("orders.create", "/orders/create", "orders.create.link")
            .gate(NavigationGate.excludes(JOB_ORDERS, OWN_JOB_ORDERS))
            .testId("nav-orders-create"),
        of("orders.demand", "/orders/material-demand", "nav.orders.materialDemand")
            .keywords("palette.keywords.materialDemand")
            .gate(NavigationGate.requires(JOB_ORDERS))
            .testId("nav-orders-material-demand")
            .with(PALETTE_ONLY),
        of("blueprints.availability", "/blueprint-overview", "nav.blueprint_overview")
            .keywords("palette.keywords.blueprintOverview")
            .gate(NavigationGate.requires(BLUEPRINT_OVERVIEW))
            .testId("nav-blueprint-overview")
            .with(PALETTE_ONLY));
  }

  @NotNull
  private static NavigationGroup buildTrade() {
    return NavigationGroup.of(
        "trade",
        "nav.materials",
        ICON_SWAP,
        of("materials.list", "/materials", "nav.page.material")
            .tab("nav.tab.materialList")
            .keywords("palette.keywords.materials"),
        of("materials.overview", "/materials/overview", "nav.materials.overview")
            .tab("nav.tab.materialOverview")
            .with(PALETTE_ONLY),
        of("materials.profit", "/materials/profit-calculation", "nav.materials.profit_calculation")
            .keywords("palette.keywords.profit")
            .with(PALETTE_ONLY),
        of("materialboerse.board", "/materialboerse", "nav.materialboerse")
            .keywords("palette.keywords.materialboerse")
            .testId("nav-materialboerse")
            .gate(NavigationGate.anyRole(Roles.KRT_MEMBER)),
        of("supply.needs", "/supply/needs", "nav.section.supply")
            .tab("nav.supply.needs")
            .keywords("palette.keywords.supplyNeeds")
            .gate(NavigationGate.requires(SUPPLY))
            .testId("nav-supply-needs"),
        of("purchasing.deals", "/purchasing/deals", "nav.section.supply")
            .tab("nav.purchasing.deals")
            .keywords("palette.keywords.purchasing")
            .gate(NavigationGate.requires(PURCHASING).andExcludes(SUPPLY))
            .testId("nav-purchasing-deals"),
        of("supply.donations", "/supply/donations/mine", "nav.supply.mine")
            .keywords("palette.keywords.supplyMine")
            .gate(NavigationGate.requires(SUPPLY))
            .testId("nav-supply-mine")
            .with(PALETTE_ONLY));
  }

  @NotNull
  private static NavigationGroup buildUnit() {
    return NavigationGroup.of(
        "unit",
        "nav.group.unit",
        ICON_USERS,
        of("orgchart.view", "/org-chart", "nav.section.organisation")
            .tab("nav.orgChart")
            .keywords("palette.keywords.orgChart")
            .testId("nav-org-chart"),
        of("orgunitbank.overview", "/org-unit-bank", "nav.bank.myOrgUnit")
            .tab("nav.tab.unitBank")
            .keywords("palette.keywords.bank")
            .gate(
                NavigationGate.anyRole(
                    Roles.ADMIN, Roles.LOGISTICIAN, Roles.MISSION_MANAGER, Roles.KRT_MEMBER))
            .testId("nav-org-unit-bank")
            .with(PALETTE_ONLY),
        of("organisation.leadership", "/organisation/leitung", "nav.leitung")
            .gate(LEITUNG_GATE)
            .testId("nav-leitung")
            .with(PALETTE_ONLY),
        of("promotion.overview", "/promotion/overview", "nav.promotion")
            .tab("nav.tab.overview")
            .keywords("palette.keywords.promotion")
            .gate(NavigationGate.requires(PROMOTION)),
        of("promotion.evaluations", "/promotion/my-evaluations", "nav.promotion.my_evaluations")
            .gate(NavigationGate.requires(PROMOTION))
            .with(PALETTE_ONLY),
        of("promotion.manage", "/promotion/manage", "nav.promotion.manage")
            .tab("nav.tab.manage")
            .gate(PROMOTION_MANAGEMENT_GATE.andRequires(PROMOTION))
            .with(PALETTE_ONLY),
        of("promotion.topics", "/promotion/admin/topics", "nav.promotion.admin_topics")
            .gate(PROMOTION_MANAGEMENT_GATE.andRequires(PROMOTION))
            .with(PALETTE_ONLY),
        of("promotion.ranks", "/promotion/admin/rank-requirements", "nav.promotion.admin_ranks")
            .gate(PROMOTION_MANAGEMENT_GATE.andRequires(PROMOTION))
            .with(PALETTE_ONLY));
  }

  @NotNull
  private static NavigationGroup buildBank() {
    return NavigationGroup.of(
            "bank",
            "nav.section.bank",
            ICON_BANK,
            of("bank.dashboard", "/bank", "nav.section.bank")
                .tab("nav.tab.dashboard")
                .keywords("palette.keywords.bank")
                .testId("nav-bank"),
            of("bank.manage", "/bank/manage", "nav.bank.manage")
                .testId("nav-bank-manage")
                .with(PALETTE_ONLY),
            of("bank.grants", "/bank/grants", "nav.bank.grants")
                .gate(NavigationGate.anyRole(Roles.BANK_MANAGEMENT))
                .testId("nav-bank-grants")
                .with(PALETTE_ONLY),
            of("bank.requests", "/bank/requests", "nav.bank.requests")
                .keywords("palette.keywords.bankRequests")
                .testId("nav-bank-requests")
                .with(PALETTE_ONLY))
        .gate(NavigationGate.anyRole(Roles.BANK_EMPLOYEE));
  }

  @NotNull
  private static NavigationGroup buildResources() {
    return NavigationGroup.of(
        "resources",
        "nav.section.resources",
        ICON_EXTERNAL,
        of("sclinks.list", "/sc-links", "nav.scLinks")
            .keywords("palette.keywords.scLinks")
            .testId("nav-sc-links")
            .with(PALETTE_ONLY));
  }

  @NotNull
  private static NavigationGroup buildPersonal() {
    return NavigationGroup.of(
        "personal",
        "nav.section.personal",
        ICON_USER,
        of("personal.inventory", "/personal-inventory", "nav.personal.items")
            .keywords("palette.keywords.personalItems"),
        of("personal.blueprints", "/personal-inventory/blueprints", "nav.personal.blueprints")
            .keywords("palette.keywords.personalBlueprints"),
        of("personal.profile", "/profile", "nav.profile").keywords("palette.keywords.profile"),
        of("personal.connectedapps", "/connected-apps", "nav.personal.connectedApps")
            .keywords("palette.keywords.connectedApps")
            .testId("nav-connected-apps"));
  }

  @NotNull
  private static NavigationGroup buildAdminTop() {
    return NavigationGroup.of(
            "admin-hub",
            "nav.admin.dashboard",
            ICON_GEAR,
            of("admin.dashboard", "/admin", "nav.admin.dashboard")
                .icon(ICON_GEAR)
                .testId("nav-admin-dashboard"))
        .gate(NavigationGate.anyRole(Roles.ADMIN));
  }

  @NotNull
  private static List<NavigationGroup> buildAdmin() {
    NavigationGate adminOnly = NavigationGate.anyRole(Roles.ADMIN);
    return List.of(
        NavigationGroup.of(
                "admin-members",
                "nav.admin.group.members",
                ICON_USERS,
                of("admin.members", "/members", "nav.members").keywords("palette.keywords.members"),
                of("admin.orgstructure", "/admin/org-structure", "nav.admin.orgStructure")
                    .testId("nav-admin-org-structure"),
                of("admin.privacy", "/admin/person-search", "nav.admin.privacy")
                    .testId("nav-admin-person-search"),
                of("admin.members.list", "/members", "nav.admin.tab.members").with(PALETTE_ONLY),
                of(
                        "admin.discordregistrations",
                        "/admin/discord-registrations",
                        "nav.admin.discordRegistrations")
                    .with(PALETTE_ONLY),
                of("admin.terms", "/admin/terms", "nav.admin.terms").with(PALETTE_ONLY),
                of(
                        "admin.orgstructure.structure",
                        "/admin/org-structure",
                        "nav.admin.tab.structure")
                    .with(PALETTE_ONLY),
                of(
                        "admin.squadrons",
                        "/admin/org-structure/squadrons",
                        "admin.mission_data.squadrons")
                    .with(PALETTE_ONLY),
                of("admin.specialcommands", "/admin/special-commands", "nav.admin.specialcommands")
                    .with(PALETTE_ONLY),
                of("admin.personsearch", "/admin/person-search", "nav.admin.personSearch")
                    .with(PALETTE_ONLY),
                of(
                        "admin.deletionrequests",
                        "/admin/deletion-requests",
                        "nav.admin.deletionRequests")
                    .with(PALETTE_ONLY),
                of("admin.announcement", "/admin/announcement", "info.title").with(PALETTE_ONLY))
            .gate(adminOnly)
            .startCollapsed(),
        NavigationGroup.of(
                "admin-masterdata",
                "nav.admin.group.masterdata",
                ICON_DATABASE,
                of("admin.missiondata", "/admin/mission-data", "nav.mission_data"),
                of("admin.locations", "/admin/locations", "nav.locations"),
                of("admin.shipdata", "/ship-data", "nav.hangar_data"),
                of("admin.materials", "/admin/materials", "nav.admin.materials"),
                of("admin.blueprints", "/admin/blueprints", "nav.admin.blueprints"),
                of("admin.dataimports", "/admin/p4k-import", "nav.admin.dataImports"),
                of("admin.uex", "/admin/uex-data", "nav.admin.uex").with(PALETTE_ONLY),
                of("admin.materialaliases", "/admin/material-aliases", "nav.admin.materialAliases")
                    .with(PALETTE_ONLY),
                of("admin.qualitytiers", "/admin/quality-tiers", "nav.admin.qualityTiers")
                    .testId("nav-admin-quality-tiers")
                    .with(PALETTE_ONLY),
                of(
                        "admin.defaultblueprints",
                        "/admin/default-blueprints",
                        "nav.admin.defaultBlueprints")
                    .with(PALETTE_ONLY),
                of("admin.p4kimport", "/admin/p4k-import", "nav.admin.p4kImport")
                    .with(PALETTE_ONLY),
                of("admin.syncreports", "/admin/sync-reports", "nav.admin.syncReports")
                    .with(PALETTE_ONLY),
                of("admin.syncreports.uex", "/admin/sync-reports/uex", "palette.page.syncUex")
                    .keywords("palette.keywords.syncReports")
                    .with(PALETTE_ONLY),
                of(
                        "admin.syncreports.scwiki",
                        "/admin/sync-reports/scwiki",
                        "palette.page.syncScWiki")
                    .keywords("palette.keywords.syncReports")
                    .with(PALETTE_ONLY))
            .gate(adminOnly)
            .startCollapsed(),
        NavigationGroup.of(
                "admin-operations",
                "nav.admin.group.system",
                ICON_GEAR,
                of("admin.auditlog", "/admin/audit-log", "nav.admin.auditLog")
                    .keywords("palette.keywords.auditLog")
                    .testId("nav-admin-audit-log"),
                of("admin.settings", "/admin/settings", "nav.admin.settings")
                    .keywords("palette.keywords.settings"),
                of("admin.exchangeclients", "/admin/exchange-clients", "nav.admin.exchangeClients")
                    .testId("nav-admin-exchange-clients"),
                of(
                    "admin.notificationrules",
                    "/admin/notification-rules",
                    "nav.admin.notificationRules"),
                of("admin.settings.general", "/admin/settings", "nav.admin.tab.general")
                    .with(PALETTE_ONLY),
                of("admin.supply", "/admin/supply", "nav.admin.supply")
                    .testId("nav-admin-supply")
                    .with(PALETTE_ONLY),
                of("admin.purchasing", "/admin/purchasing", "nav.admin.purchasing")
                    .testId("nav-admin-purchasing")
                    .with(PALETTE_ONLY),
                of("admin.dangerzone", "/admin/settings/danger-zone", "nav.admin.tab.dangerZone")
                    .with(PALETTE_ONLY))
            .gate(adminOnly)
            .startCollapsed());
  }

  @NotNull
  private static List<NavigationPageTabs> buildPageTabs() {
    return List.of(
        NavigationPageTabs.of("missions.list", "operations.list"),
        NavigationPageTabs.of("hangar.overview", "hangar.catalog"),
        NavigationPageTabs.of("storage.overview", "storage.levels"),
        NavigationPageTabs.of(
            "orders.list",
            "orders.own",
            "orders.assigned",
            "orders.demand",
            "blueprints.availability"),
        NavigationPageTabs.of("materials.list", "materials.overview", "materials.profit"),
        NavigationPageTabs.of("supply.needs", "supply.donations", "purchasing.deals"),
        NavigationPageTabs.of("orgchart.view", "orgunitbank.overview", "organisation.leadership"),
        NavigationPageTabs.of("promotion.overview", "promotion.evaluations", "promotion.manage")
            .covering("promotion.manage", "promotion.topics", "promotion.ranks"),
        NavigationPageTabs.of("bank.dashboard", "bank.manage", "bank.grants", "bank.requests"),
        NavigationPageTabs.of("admin.members.list", "admin.discordregistrations", "admin.terms"),
        NavigationPageTabs.of(
            "admin.orgstructure.structure", "admin.squadrons", "admin.specialcommands"),
        NavigationPageTabs.of("admin.personsearch", "admin.deletionrequests"),
        NavigationPageTabs.of("admin.locations", "admin.uex"),
        NavigationPageTabs.of("admin.materials", "admin.materialaliases", "admin.qualitytiers"),
        NavigationPageTabs.of("admin.blueprints", "admin.defaultblueprints"),
        NavigationPageTabs.of("admin.p4kimport", "admin.syncreports"),
        NavigationPageTabs.of(
            "admin.settings.general", "admin.supply", "admin.purchasing", "admin.dangerzone"));
  }

  @NotNull
  private static NavigationGroup buildAnonymous() {
    return NavigationGroup.of(
        "public",
        "nav.group.general",
        null,
        of("public.home", "/", "nav.home"),
        of("public.login", "/oauth2/authorization/keycloak", "nav.login").testId("nav-login"),
        of(
                "public.login.discord",
                "/oauth2/authorization/keycloak?idp=discord",
                "nav.login.discord")
            .testId("nav-login-discord")
            .icon("discord")
            .with(LABEL_WITH_ICON));
  }

  @NotNull
  private static NavigationGroup buildLegal() {
    return NavigationGroup.of(
        "legal",
        "nav.legal",
        "info",
        of("legal.impressum", "/impressum", "nav.impressum"),
        of("legal.privacy", "/privacy", "nav.privacy"),
        of("legal.terms", "/terms", "nav.terms"),
        of("legal.licenses", "/licenses", "nav.licenses"),
        of("legal.handbook", HANDBOOK_URL, "footer.handbook").with(EXTERNAL, NOT_INDEXED));
  }

  @NotNull
  private static NavigationGroup buildFanKit() {
    return NavigationGroup.of(
        "fankit",
        "nav.legal",
        "info",
        of("fankit.officialsite", "https://robertsspaceindustries.com/", "fankit.officialSite")
            .with(EXTERNAL, NOT_INDEXED));
  }

  @NotNull
  private static NavigationGroup buildActions() {
    return NavigationGroup.of(
        "actions",
        "palette.actions",
        ICON_PLUS,
        of("action.order.create", "/orders/create", "palette.action.createOrder")
            .keywords("palette.keywords.create")
            .icon(ICON_PLUS)
            .testId("palette-action-create-order"),
        of("action.mission.create", "/missions/new", "palette.action.createMission")
            .keywords("palette.keywords.create")
            .icon(ICON_PLUS)
            .testId("palette-action-create-mission"),
        of("action.refinery.create", "/refinery-orders/create", "palette.action.createRefinery")
            .keywords("palette.keywords.create")
            .icon(ICON_PLUS)
            .testId("palette-action-create-refinery"),
        of("action.storage.bookmy", "/inventory/input?source=my", "palette.action.bookMy")
            .keywords("palette.keywords.book")
            .icon(ICON_PLUS)
            .testId("palette-action-book-my"),
        of(
                "action.storage.bookglobal",
                "/inventory/input?source=admin",
                "palette.action.bookGlobal")
            .keywords("palette.keywords.book")
            .icon(ICON_PLUS)
            .testId("palette-action-book-global")
            .gate(NavigationGate.requires(LOGISTICIAN_HERE)));
  }

  @NotNull
  private static NavigationGroup buildPaletteTail() {
    return NavigationGroup.of(
        "tail",
        "palette.actions",
        null,
        of("action.handbook", HANDBOOK_URL, "palette.action.handbook")
            .keywords("palette.keywords.handbook")
            .icon(ICON_EXTERNAL)
            .testId("palette-action-handbook")
            .with(EXTERNAL),
        of("action.language.de", "?lang=de", "palette.action.langDe")
            .keywords("palette.keywords.language")
            .icon("swap")
            .testId("palette-action-lang-de")
            .gate(NavigationGate.excludes(LANGUAGE_DE)),
        of("action.language.en", "?lang=en", "palette.action.langEn")
            .keywords("palette.keywords.language")
            .icon("swap")
            .testId("palette-action-lang-en")
            .gate(NavigationGate.excludes(LANGUAGE_EN)));
  }

  @NotNull
  private static List<NavigationTab> buildTabs() {
    return List.of(
        new NavigationTab(List.of("missions.list")),
        new NavigationTab(List.of("storage.overview")),
        new NavigationTab(
            List.of("orders.list", "orders.own", "orders.assigned", "orders.create")));
  }

  @NotNull
  private static List<NavigationTab> buildAdminTabs() {
    return List.of(
        new NavigationTab(List.of("admin.dashboard")),
        new NavigationTab(List.of("admin.members")),
        new NavigationTab(List.of("admin.auditlog")));
  }
}
