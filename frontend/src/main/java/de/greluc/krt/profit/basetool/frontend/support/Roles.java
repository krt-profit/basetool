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

package de.greluc.krt.profit.basetool.frontend.support;

import java.util.Set;

/**
 * Central constant holder for role codes on the frontend side (S3 Phase 3, part of #909), replacing
 * the raw string literals that used to be copy-pasted across {@code @PreAuthorize} expressions,
 * {@code sec:authorize} template attributes' Java counterparts and {@code
 * FrontendAuthHelperService}'s literal authority comparisons.
 *
 * <p>The frontend module cannot depend on the backend's {@code support.Roles} (separate Gradle
 * module — the frontend never talks to the backend's Java code directly, only via {@code
 * BackendApiClient}), so the values are intentionally duplicated here. They must stay
 * <b>byte-identical</b> to the backend's {@code support.Roles} — a changed constant here is a
 * breaking change against the bearer-token relay, which forwards Keycloak/backend-issued
 * authorities to the frontend verbatim.
 *
 * <p>The frontend does not configure a role hierarchy of its own ({@link
 * de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService}) — every authority
 * check here is a literal match against the authorities the bearer-token relay forwarded, never a
 * reachability computation.
 */
public final class Roles {

  /** Non-instantiable static-constant holder. */
  private Roles() {}

  /** Prefix Spring Security authorities carry; {@code hasRole(...)} strips/re-adds it itself. */
  public static final String ROLE_PREFIX = "ROLE_";

  // --- Bare role codes, mirroring the backend's support.Roles (must stay byte-identical) --------

  public static final String ADMIN = "ADMIN";
  public static final String OFFICER = "OFFICER";
  public static final String KRT_MEMBER = "KRT_MEMBER";
  public static final String BANK_EMPLOYEE = "BANK_EMPLOYEE";
  public static final String BANK_MANAGEMENT = "BANK_MANAGEMENT";
  public static final String LOGISTICIAN = "LOGISTICIAN";
  public static final String MISSION_MANAGER = "MISSION_MANAGER";

  /**
   * Returns the {@code ROLE_}-prefixed Spring-authority form of a bare role code — the form every
   * frontend authority literally carries (the bearer-token relay forwards authorities verbatim, so
   * unlike {@code hasRole(...)} SpEL, direct {@code GrantedAuthority} comparisons here need the
   * prefixed string).
   *
   * @param code a bare role code, e.g. {@link #ADMIN}
   * @return the prefixed authority, e.g. {@code "ROLE_ADMIN"}
   */
  public static String authority(String code) {
    return ROLE_PREFIX + code;
  }

  /**
   * The "registered member or above" authority set: holding any one of these marks the caller as an
   * organisation member or above. Kept in sync with the backend role matrix in {@code
   * ROLES_AND_PERMISSIONS.md}.
   *
   * <p><b>Not "all roles."</b> {@link #BANK_EMPLOYEE} and {@link #BANK_MANAGEMENT} are deliberately
   * excluded — a bank-only authority does not by itself make the caller an organisation member. Do
   * not widen this to a generic "every constant in this class" helper; that would silently admit
   * both into every member-or-above check.
   *
   * <p>{@code GUEST} was a third exclusion until ADR-0159 removed the role. Nothing holds an empty
   * authority set any more: such a token is refused with {@code 403 NO_ROLE} (REQ-SEC-053) before
   * a request reaches a handler.
   */
  public static final Set<String> MEMBER_AUTHORITIES =
      Set.of(
          authority(ADMIN),
          authority(OFFICER),
          authority(MISSION_MANAGER),
          authority(LOGISTICIAN),
          authority(KRT_MEMBER));

  /**
   * Pre-built {@code @PreAuthorize} SpEL expression for the "admin or officer" gate repeated
   * verbatim across the promotion proxy/page controllers. A compile-time constant per JLS
   * 4.12.4/15.28, so {@code @PreAuthorize(Roles.ADMIN_OR_OFFICER)} is a legal annotation value.
   */
  public static final String ADMIN_OR_OFFICER = "hasAnyRole('" + ADMIN + "','" + OFFICER + "')";
}
