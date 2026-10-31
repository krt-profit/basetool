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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * The condition under which a navigation entry or group is rendered: at least one of the roles
 * (none listed means any caller), every required flag and none of the excluded flags.
 *
 * <p>Gates only steer the UI; the page's own handler gate stays authoritative.
 *
 * @param anyRole bare role codes of which the caller must hold one, or empty for no role condition
 * @param required flags the caller must all have
 * @param excluded flags the caller must have none of
 */
public record NavigationGate(
    @NotNull Set<String> anyRole,
    @NotNull Set<NavigationFlag> required,
    @NotNull Set<NavigationFlag> excluded) {

  /** The gate every caller of the zone passes. */
  public static final NavigationGate ALWAYS = new NavigationGate(Set.of(), Set.of(), Set.of());

  /**
   * Copies the sets so the gate stays immutable.
   *
   * @param anyRole bare role codes of which the caller must hold one
   * @param required flags the caller must all have
   * @param excluded flags the caller must have none of
   */
  public NavigationGate {
    anyRole = Set.copyOf(anyRole);
    required = Set.copyOf(required);
    excluded = Set.copyOf(excluded);
  }

  /**
   * A gate that needs one of the roles.
   *
   * @param roles bare role codes, e.g. {@code ADMIN}
   * @return the gate
   */
  @NotNull
  public static NavigationGate anyRole(@NotNull String... roles) {
    return new NavigationGate(Set.of(roles), Set.of(), Set.of());
  }

  /**
   * A gate that needs every one of the flags.
   *
   * @param flags the required flags
   * @return the gate
   */
  @NotNull
  public static NavigationGate requires(@NotNull NavigationFlag... flags) {
    return ALWAYS.andRequires(flags);
  }

  /**
   * A gate that needs none of the flags.
   *
   * @param flags the excluded flags
   * @return the gate
   */
  @NotNull
  public static NavigationGate excludes(@NotNull NavigationFlag... flags) {
    return ALWAYS.andExcludes(flags);
  }

  /**
   * This gate with further required flags.
   *
   * @param flags the additional required flags
   * @return the narrowed gate
   */
  @NotNull
  public NavigationGate andRequires(@NotNull NavigationFlag... flags) {
    return new NavigationGate(anyRole, union(required, flags), excluded);
  }

  /**
   * This gate with further excluded flags.
   *
   * @param flags the additional excluded flags
   * @return the narrowed gate
   */
  @NotNull
  public NavigationGate andExcludes(@NotNull NavigationFlag... flags) {
    return new NavigationGate(anyRole, required, union(excluded, flags));
  }

  /**
   * Whether a caller passes this gate.
   *
   * @param access the caller's reachable roles and flags
   * @return {@code true} iff the role condition holds, every required flag is set and no excluded
   *     flag is
   */
  public boolean permits(@NotNull NavigationAccess access) {
    boolean roleHeld = anyRole.isEmpty() || anyRole.stream().anyMatch(access.roles()::contains);
    return roleHeld
        && access.flags().containsAll(required)
        && excluded.stream().noneMatch(access.flags()::contains);
  }

  @NotNull
  private static Set<NavigationFlag> union(
      @NotNull Set<NavigationFlag> base, @NotNull NavigationFlag... more) {
    return Stream.concat(base.stream(), Stream.of(more))
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(NavigationFlag.class)));
  }
}
