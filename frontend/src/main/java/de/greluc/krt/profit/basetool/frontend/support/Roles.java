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
import org.jetbrains.annotations.NotNull;

/**
 * Role-code constants for the frontend; they must stay identical to the backend's {@code
 * support.Roles}, since authorities are relayed verbatim.
 *
 * <p>The frontend has no role hierarchy: every check is a literal match against the relayed
 * authorities.
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
  public static final String LOGISTICIAN = "LOGISTICIAN";
  public static final String MISSION_MANAGER = "MISSION_MANAGER";

  /**
   * Returns the {@code ROLE_}-prefixed authority for a bare role code, the form relayed authorities
   * carry.
   *
   * @param code a bare role code, e.g. {@link #ADMIN}
   * @return the prefixed authority, e.g. {@code "ROLE_ADMIN"}
   */
  @NotNull
  public static String authority(String code) {
    return ROLE_PREFIX + code;
  }

  /**
   * The authorities that mark the caller as an organisation member or above. {@link #BANK_EMPLOYEE}
   * and {@link #BANK_MANAGEMENT} are deliberately excluded.
   */
  public static final Set<String> MEMBER_AUTHORITIES =
      Set.of(
          authority(ADMIN),
          authority(OFFICER),
          authority(MISSION_MANAGER),
          authority(LOGISTICIAN),
          authority(KRT_MEMBER));

  /**
   * {@code @PreAuthorize} expression for the "admin or officer" gate; a compile-time constant
   * usable as an annotation value.
   */
  public static final String ADMIN_OR_OFFICER = "hasAnyRole('" + ADMIN + "','" + OFFICER + "')";
}
