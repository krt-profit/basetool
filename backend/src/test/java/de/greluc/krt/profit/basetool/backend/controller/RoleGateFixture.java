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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.config.SecurityConfig;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import java.util.List;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Shared fixture for controller tests that verify a role branch decided through {@link
 * AuthHelperService} against the raw authority check it replaces, for every caller in {@link
 * #callers()}.
 */
final class RoleGateFixture {

  /** Marker in {@link #callers()} for a request that carries no authentication at all. */
  static final String NONE = "none";

  /** Marker in {@link #callers()} for Spring Security's anonymous principal. */
  static final String ANONYMOUS = "anonymous";

  private RoleGateFixture() {}

  /**
   * Builds the helper the production context wires: the real {@link SecurityConfig#roleHierarchy()}
   * and no application context, which none of the role predicates touch.
   *
   * @return a real {@link AuthHelperService} over the production role hierarchy
   */
  @NotNull
  static AuthHelperService realAuthHelper() {
    return new AuthHelperService(SecurityConfig.roleHierarchy(), null);
  }

  /**
   * Every caller shape the gates can meet: each application authority on its own, the ingest
   * gateway's machine authority, Spring's anonymous principal, and no authentication at all.
   *
   * @return the authority (or {@link #NONE} / {@link #ANONYMOUS}) of each caller shape
   */
  @NotNull
  static Stream<String> callers() {
    return Stream.of(
        "ROLE_ADMIN",
        "ROLE_OFFICER",
        "ROLE_MISSION_MANAGER",
        "ROLE_LOGISTICIAN",
        "ROLE_KRT_MEMBER",
        "ROLE_BANK_MANAGEMENT",
        "ROLE_BANK_EMPLOYEE",
        "ROLE_INGEST_GATEWAY",
        ANONYMOUS,
        NONE);
  }

  /**
   * Installs the caller shape named by {@code caller} into the thread's security context, the way
   * the filter chain does for a real request.
   *
   * @param caller an authority from {@link #callers()}, {@link #ANONYMOUS} or {@link #NONE}
   */
  static void authenticateAs(@NotNull String caller) {
    SecurityContextHolder.clearContext();
    Authentication authentication = toAuthentication(caller);
    if (authentication != null) {
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }
  }

  /** Removes whatever {@link #authenticateAs(String)} installed. */
  static void clear() {
    SecurityContextHolder.clearContext();
  }

  /**
   * Whether {@code caller} carries one of {@code authorities} directly, the raw authority-match
   * predicate.
   *
   * @param caller an authority from {@link #callers()}, {@link #ANONYMOUS} or {@link #NONE}
   * @param authorities the authorities the raw check accepted
   * @return {@code true} iff the caller's single authority is one of {@code authorities}
   */
  static boolean rawCheckAccepted(@NotNull String caller, @NotNull String... authorities) {
    return List.of(authorities).contains(caller);
  }

  @Nullable
  private static Authentication toAuthentication(@NotNull String caller) {
    return switch (caller) {
      case NONE -> null;
      case ANONYMOUS ->
          new AnonymousAuthenticationToken(
              "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
      default ->
          UsernamePasswordAuthenticationToken.authenticated(
              "caller", "n/a", List.of(new SimpleGrantedAuthority(caller)));
    };
  }
}
