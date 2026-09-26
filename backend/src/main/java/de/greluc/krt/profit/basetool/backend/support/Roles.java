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

import org.jetbrains.annotations.NotNull;

/**
 * Central constants for the bare role codes, identical to {@code Role.code} and to the Keycloak
 * realm role names without the {@code ROLE_} prefix; changing a value is a breaking change.
 *
 * <p>{@link #authority(String)} derives the prefixed Spring-authority form. {@code LOGISTICIAN} and
 * {@code MISSION_MANAGER} are not seeded roles but derived from user flags. Lives in the
 * dependency-leaf {@code support} package (ADR-0047).
 */
public final class Roles {

  /** Non-instantiable static-constant holder. */
  private Roles() {}

  /** Prefix Spring Security authorities carry; {@code hasRole(...)} strips/re-adds it itself. */
  public static final String ROLE_PREFIX = "ROLE_";

  public static final String ADMIN = "ADMIN";
  public static final String OFFICER = "OFFICER";
  public static final String KRT_MEMBER = "KRT_MEMBER";
  public static final String BANK_EMPLOYEE = "BANK_EMPLOYEE";
  public static final String BANK_MANAGEMENT = "BANK_MANAGEMENT";

  /**
   * The marker authority of an approved account holding no application role (REQ-SEC-053), already
   * {@code ROLE_}-prefixed.
   *
   * <p>Not in the role hierarchy; {@code PendingApprovalAccessFilter} turns it into {@code 403
   * NO_ROLE} before any handler runs.
   */
  public static final String NO_ROLE_MARKER = ROLE_PREFIX + "NO_ROLE";

  public static final String LOGISTICIAN = "LOGISTICIAN";
  public static final String MISSION_MANAGER = "MISSION_MANAGER";

  /**
   * Returns the {@code ROLE_}-prefixed Spring-authority form of a bare role code, for call sites
   * that cannot use the {@code hasRole(...)} shorthand.
   *
   * @param code a bare role code, e.g. {@link #ADMIN}
   * @return the prefixed authority, e.g. {@code "ROLE_ADMIN"}
   */
  @NotNull
  public static String authority(String code) {
    return ROLE_PREFIX + code;
  }

  /** Compile-time {@code @PreAuthorize} SpEL expression for the admin-or-officer gate. */
  public static final String ADMIN_OR_OFFICER = "hasAnyRole('" + ADMIN + "','" + OFFICER + "')";

  /** Compile-time {@code @PreAuthorize} SpEL expression for the {@code hasRole('ADMIN')} gate. */
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
