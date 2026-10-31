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
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationFlag;
import java.util.EnumSet;
import java.util.Locale;
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
 * Exposes {@code navigation}, the caller's {@link NavigationMenu}, to every page that renders the
 * layout; the navigation fragments render nothing but it.
 *
 * <p>Roles are read through the role hierarchy, as {@code sec:authorize} reads them; the flags come
 * from the other layout advices' model attributes and fail closed when one is missing.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
public class NavigationAdvice {

  private final NavigationRegistry registry;

  private final RoleHierarchy roleHierarchy;

  /**
   * Resolves the navigation for the current request.
   *
   * @param authentication the caller's authentication, or {@code null} for a signed-out visitor
   * @param locale the request locale
   * @param capabilities the caller's capability flags
   * @param supplyEnabled whether the supply surface is on
   * @param purchasingEnabled whether the purchasing surface is on
   * @param promotionEnabled whether the promotion subsystem is exposed
   * @return the caller's navigation; never {@code null}
   */
  @ModelAttribute("navigation")
  @NotNull
  public NavigationMenu navigation(
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
      addIf(flags, capabilities.canOpenLeitung(), NavigationFlag.OPEN_LEITUNG);
      addIf(flags, capabilities.canManagePromotions(), NavigationFlag.MANAGE_PROMOTIONS);
    }
    addIf(flags, Boolean.TRUE.equals(supplyEnabled), NavigationFlag.SUPPLY);
    addIf(flags, Boolean.TRUE.equals(purchasingEnabled), NavigationFlag.PURCHASING);
    addIf(flags, Boolean.TRUE.equals(promotionEnabled), NavigationFlag.PROMOTION);
    addIf(flags, "de".equals(locale.getLanguage()), NavigationFlag.LANGUAGE_DE);
    addIf(flags, "en".equals(locale.getLanguage()), NavigationFlag.LANGUAGE_EN);
    return NavigationMenu.resolve(registry, new NavigationAccess(roles(authentication), flags));
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
