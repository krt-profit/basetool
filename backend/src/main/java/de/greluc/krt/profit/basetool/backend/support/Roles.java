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

package de.greluc.krt.profit.basetool.backend.support;

/**
 * Central constant holder for role codes, replacing the raw string literals (S3, part of #905) that
 * used to be copy-pasted across {@code SecurityConfig}, {@code DataInitializer} and every
 * {@code @PreAuthorize} expression, where a rename or typo failed silently with no compile check.
 *
 * <p>Values are <b>byte-identical</b> to the existing wire contract and must stay that way — a
 * changed constant here is a breaking change. Every constant is the bare code: {@code Role.code} as
 * persisted by {@code DataInitializer} / the role-management screens, and the Keycloak realm role
 * name minus its {@code ROLE_} prefix. The handful of call sites that need the {@code
 * ROLE_}-prefixed Spring-authority form ({@code CustomJwtGrantedAuthoritiesConverter} prepends
 * {@code ROLE_}; the {@link
 * de.greluc.krt.profit.basetool.backend.config.SecurityConfig#roleHierarchy()} chain, where {@code
 * RoleHierarchyImpl.fromHierarchy(...)} expects the prefixed form on both sides) derive it via
 * {@link #authority(String)} rather than a duplicated {@code ROLE_*} constant per role.
 *
 * <p>{@code LOGISTICIAN} and {@code MISSION_MANAGER} are <b>not</b> seeded in the {@code Role}
 * table — {@code CustomJwtGrantedAuthoritiesConverter} derives them from the {@code app_user}
 * {@code is_logistician} / {@code is_mission_manager} flags, and {@link
 * de.greluc.krt.profit.basetool.backend.config.SecurityConfig#roleHierarchy()} additionally grants
 * them to {@code ADMIN} / {@code OFFICER} via the role hierarchy.
 *
 * <p>Lives in the dependency-leaf {@code support} package (ADR-0047) — plain {@code String}
 * constants with no dependency on the security API, {@code model} or {@code service}.
 */
public final class Roles {

  /** Non-instantiable static-constant holder. */
  private Roles() {}

  /** Prefix Spring Security authorities carry; {@code hasRole(...)} strips/re-adds it itself. */
  public static final String ROLE_PREFIX = "ROLE_";

  // --- Role.code values (DB-seeded, Keycloak realm role names minus the ROLE_ prefix) ----------

  public static final String ADMIN = "ADMIN";
  public static final String OFFICER = "OFFICER";
  public static final String KRT_MEMBER = "KRT_MEMBER";
  public static final String GUEST = "GUEST";
  public static final String BANK_EMPLOYEE = "BANK_EMPLOYEE";
  public static final String BANK_MANAGEMENT = "BANK_MANAGEMENT";

  // --- Hierarchy-derived roles (never seeded; see class Javadoc) ---------------------------------

  public static final String LOGISTICIAN = "LOGISTICIAN";
  public static final String MISSION_MANAGER = "MISSION_MANAGER";

  /**
   * Returns the {@code ROLE_}-prefixed Spring-authority form of a bare role code, for the handful
   * of call sites that need the literal prefixed string rather than the {@code hasRole(...)}
   * shorthand: the {@link
   * de.greluc.krt.profit.basetool.backend.config.SecurityConfig#roleHierarchy()} chain and a {@code
   * hasAnyAuthority(...)} call that mixes permission strings with a role.
   *
   * @param code a bare role code, e.g. {@link #ADMIN}
   * @return the prefixed authority, e.g. {@code "ROLE_ADMIN"}
   */
  public static String authority(String code) {
    return ROLE_PREFIX + code;
  }

  /**
   * Pre-built {@code @PreAuthorize} SpEL expression for the "admin or officer" gate repeated
   * verbatim across the promotion/rank/evaluation surface (member evaluations, promotion
   * categories/topics/level content/eligibility, rank requirements, hangar admin). A compile-time
   * constant per JLS 4.12.4/15.28, so {@code @PreAuthorize(Roles.ADMIN_OR_OFFICER)} is a legal
   * annotation value — collapses ~24 identical splices into one referenced literal.
   */
  public static final String ADMIN_OR_OFFICER = "hasAnyRole('" + ADMIN + "','" + OFFICER + "')";

  /**
   * Pre-built {@code @PreAuthorize} SpEL for the single-role {@code hasRole('ADMIN')} gate — a
   * compile-time constant per JLS 4.12.4/15.28, so {@code @PreAuthorize(Roles.HAS_ROLE_ADMIN)} is a
   * legal annotation value and the literal it inlines to is exactly what the security ArchUnit
   * rules read from the bytecode. Collapses the {@code "hasRole('" + Roles.ADMIN + "')"} splice
   * that was repeated verbatim at ~98 admin-gated endpoints into one named literal, mirroring
   * {@link #ADMIN_OR_OFFICER}.
   */
  public static final String HAS_ROLE_ADMIN = "hasRole('" + ADMIN + "')";

  /**
   * Single-role {@code hasRole('BANK_MANAGEMENT')} gate; a compile-time constant like {@link
   * #HAS_ROLE_ADMIN}.
   */
  public static final String HAS_ROLE_BANK_MANAGEMENT = "hasRole('" + BANK_MANAGEMENT + "')";

  /**
   * Single-role {@code hasRole('BANK_EMPLOYEE')} gate; a compile-time constant like {@link
   * #HAS_ROLE_ADMIN}.
   */
  public static final String HAS_ROLE_BANK_EMPLOYEE = "hasRole('" + BANK_EMPLOYEE + "')";

  /**
   * Single-role {@code hasRole('KRT_MEMBER')} gate; a compile-time constant like {@link
   * #HAS_ROLE_ADMIN}.
   */
  public static final String HAS_ROLE_KRT_MEMBER = "hasRole('" + KRT_MEMBER + "')";

  /**
   * Single-role {@code hasRole('MISSION_MANAGER')} gate; a compile-time constant like {@link
   * #HAS_ROLE_ADMIN}.
   */
  public static final String HAS_ROLE_MISSION_MANAGER = "hasRole('" + MISSION_MANAGER + "')";
}
