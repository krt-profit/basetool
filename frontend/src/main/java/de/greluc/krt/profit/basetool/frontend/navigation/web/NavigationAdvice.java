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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.CapabilityFlagsAdvice.CapabilitiesResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAppearance;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFavourites;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationHidden;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationOrder;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationPersonalisation;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationTabBar;
import jakarta.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes {@code navigation}, the caller's {@link NavigationMenu}, {@code navigationFavourites} and
 * {@code navigationHidden}, the caller's stored {@link NavigationFavourites} and {@link
 * NavigationHidden}, to every page that renders the layout; the navigation fragments render nothing
 * but them.
 *
 * <p>Roles are read through the role hierarchy, as {@code sec:authorize} reads them; the flags come
 * from the other layout advices' model attributes and fail closed when one is missing.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
public class NavigationAdvice {

  private final NavigationRegistry registry;

  private final RoleHierarchy roleHierarchy;

  private final NavigationPreferencesReader preferencesReader;

  private final NavigationPreferencesLoader preferences;

  private final FrontendAuthHelperService authHelper;

  private final NavigationTabBarResolver tabBarResolver;

  /**
   * Resolves what the caller brings to the navigation gates of the current request.
   *
   * @param authentication the caller's authentication, or {@code null} for a signed-out visitor
   * @param locale the request locale
   * @param capabilities the caller's capability flags
   * @param supplyEnabled whether the supply surface is on
   * @param purchasingEnabled whether the purchasing surface is on
   * @param promotionEnabled whether the promotion subsystem is exposed
   * @return the caller's roles and flags; never {@code null}
   */
  @ModelAttribute("navigationAccess")
  @NotNull
  public NavigationAccess access(
      @Nullable Authentication authentication,
      @NotNull Locale locale,
      @Nullable @ModelAttribute(name = "meCapabilities", binding = false)
          CapabilitiesResponse capabilities,
      @Nullable @ModelAttribute(name = "supplyEnabled", binding = false) Boolean supplyEnabled,
      @Nullable @ModelAttribute(name = "purchasingEnabled", binding = false)
          Boolean purchasingEnabled,
      @Nullable @ModelAttribute(name = "promotionFeatureEnabled", binding = false)
          Boolean promotionEnabled) {
    EnumSet<NavigationFlag> flags = EnumSet.noneOf(NavigationFlag.class);
    if (capabilities != null) {
      addIf(flags, capabilities.canSeeBlueprintOverview(), NavigationFlag.BLUEPRINT_OVERVIEW);
      addIf(flags, capabilities.canViewJobOrders(), NavigationFlag.JOB_ORDERS);
      addIf(flags, capabilities.canViewOwnJobOrders(), NavigationFlag.OWN_JOB_ORDERS);
      addIf(flags, capabilities.canViewAssignedJobOrders(), NavigationFlag.ASSIGNED_JOB_ORDERS);
      addIf(flags, capabilities.isLogisticianOrAbove(), NavigationFlag.LOGISTICIAN_HERE);
      addIf(flags, capabilities.canOpenLeitung(), NavigationFlag.OPEN_LEITUNG);
      addIf(flags, capabilities.canManagePromotions(), NavigationFlag.MANAGE_PROMOTIONS);
    }
    addIf(flags, Boolean.TRUE.equals(supplyEnabled), NavigationFlag.SUPPLY);
    addIf(flags, Boolean.TRUE.equals(purchasingEnabled), NavigationFlag.PURCHASING);
    addIf(flags, Boolean.TRUE.equals(promotionEnabled), NavigationFlag.PROMOTION);
    addIf(flags, "de".equals(locale.getLanguage()), NavigationFlag.LANGUAGE_DE);
    addIf(flags, "en".equals(locale.getLanguage()), NavigationFlag.LANGUAGE_EN);
    return new NavigationAccess(roles(authentication), flags);
  }

  /**
   * Resolves the navigation for the current request.
   *
   * @param access the caller's roles and flags
   * @return the caller's navigation; never {@code null}
   */
  @ModelAttribute("navigation")
  @NotNull
  public NavigationMenu navigation(
      @ModelAttribute(name = "navigationAccess", binding = false) @NotNull NavigationAccess access,
      @ModelAttribute(name = "navigationHidden", binding = false) @NotNull NavigationHidden hidden,
      @ModelAttribute(name = "navigationOrder", binding = false) @NotNull NavigationOrder order) {
    return NavigationMenu.resolve(registry, access, Set.copyOf(hidden.keys()), order);
  }

  /**
   * The caller's stored group and link order, for the menu and the order state.
   *
   * @param personalisation the stored preferences
   * @return the order to render
   */
  @ModelAttribute("navigationOrder")
  @NotNull
  public NavigationOrder order(
      @ModelAttribute(name = "navigationPersonalisation", binding = false) @NotNull
          NavigationPersonalisation personalisation) {
    return personalisation.order();
  }

  /**
   * Resolves the page or tab the request shows, for the header star.
   *
   * @param menu the caller's navigation
   * @param requestUri the path and query of the current request
   * @return the entry the caller can favourite here, or {@code null} on a detail page or any other
   *     URI that is no entry
   */
  @ModelAttribute("navigationCurrent")
  @Nullable
  public NavigationEntry current(
      @ModelAttribute(name = "navigation", binding = false) @NotNull NavigationMenu menu,
      @ModelAttribute(name = "currentRequestUri", binding = false) @NotNull String requestUri) {
    return menu.currentEntry(requestUri).orElse(null);
  }

  /**
   * Reads the caller's favourites and hidden pages for the navigation, once per request that
   * renders a layout model; a signed-out visitor and a handler that renders no model get none.
   *
   * @param request the current request
   * @param access the caller's roles and flags
   * @return the stored preferences to render; never {@code null}
   */
  @ModelAttribute("navigationPersonalisation")
  @NotNull
  public NavigationPersonalisation personalisation(
      @NotNull HttpServletRequest request,
      @ModelAttribute(name = "navigationAccess", binding = false) @NotNull
          NavigationAccess access) {
    if (!authHelper.isAuthenticated() || !LayoutContextLoader.needsLayoutModel(request)) {
      return NavigationPersonalisation.NOT_LOADED;
    }
    return preferencesReader.read(access);
  }

  /**
   * The caller's favourites, for the star and the Favoriten group.
   *
   * @param personalisation the stored preferences
   * @return the favourites to render
   */
  @ModelAttribute("navigationFavourites")
  @NotNull
  public NavigationFavourites favourites(
      @ModelAttribute(name = "navigationPersonalisation", binding = false) @NotNull
          NavigationPersonalisation personalisation) {
    return personalisation.favourites();
  }

  /**
   * The caller's hidden pages, for the „Mehr“ area and the menu.
   *
   * @param personalisation the stored preferences
   * @return the hidden pages to render
   */
  @ModelAttribute("navigationHidden")
  @NotNull
  public NavigationHidden hidden(
      @ModelAttribute(name = "navigationPersonalisation", binding = false) @NotNull
          NavigationPersonalisation personalisation) {
    return personalisation.hidden();
  }

  /**
   * The caller's phone tab bars, one per mode, for the tab bar and the „Anpassen“ pickers. They
   * resolve against the defaults when the preferences were not read.
   *
   * @param access the caller's roles and flags
   * @param personalisation the stored preferences
   * @return the resolved bars by mode; never {@code null}
   */
  @ModelAttribute("navigationTabBars")
  @NotNull
  public Map<String, NavigationTabBar> tabBars(
      @ModelAttribute(name = "navigationAccess", binding = false) @NotNull NavigationAccess access,
      @ModelAttribute(name = "navigationPersonalisation", binding = false) @NotNull
          NavigationPersonalisation personalisation) {
    Map<String, NavigationTabBar> bars = new LinkedHashMap<>();
    for (NavigationMode mode : NavigationMode.values()) {
      bars.put(
          mode.getSegment(),
          tabBarResolver.resolve(
              mode,
              personalisation.tabBars().of(mode),
              personalisation.favourites().loaded(),
              personalisation.favourites(),
              access));
    }
    return Map.copyOf(bars);
  }

  /**
   * Reads the caller's appearance for the navigation, from the same stored preferences as the
   * favourites; a signed-out visitor and a handler that renders no model get the drawer.
   *
   * @param request the current request
   * @return the appearance to render; never {@code null}
   */
  @ModelAttribute("navigationAppearance")
  @NotNull
  public NavigationAppearance appearance(@NotNull HttpServletRequest request) {
    if (!authHelper.isAuthenticated() || !LayoutContextLoader.needsLayoutModel(request)) {
      return NavigationAppearance.NOT_LOADED;
    }
    return preferences
        .load()
        .map(stored -> NavigationAppearance.of(stored.appearance()))
        .orElse(NavigationAppearance.NOT_LOADED);
  }

  /**
   * The start-page choices and the chosen start pages, for the header button and „Darstellung“.
   *
   * @param access the caller's roles and flags
   * @param appearance the caller's stored appearance
   * @return the choices to render; never {@code null}
   */
  @ModelAttribute("navigationStartPages")
  @NotNull
  public NavigationStartPages startPages(
      @ModelAttribute(name = "navigationAccess", binding = false) @NotNull NavigationAccess access,
      @ModelAttribute(name = "navigationAppearance", binding = false) @NotNull
          NavigationAppearance appearance) {
    return NavigationStartPages.of(registry, access, appearance);
  }

  @NotNull
  private Set<String> roles(@Nullable Authentication authentication) {
    if (authentication == null) {
      return Set.of();
    }
    return roleHierarchy.getReachableGrantedAuthorities(authentication.getAuthorities()).stream()
        .map(GrantedAuthority::getAuthority)
        .filter(authority -> authority != null && authority.startsWith(Roles.ROLE_PREFIX))
        .map(authority -> authority.substring(Roles.ROLE_PREFIX.length()))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static void addIf(
      @NotNull Set<NavigationFlag> flags, boolean condition, @NotNull NavigationFlag flag) {
    if (condition) {
      flags.add(flag);
    }
  }
}
