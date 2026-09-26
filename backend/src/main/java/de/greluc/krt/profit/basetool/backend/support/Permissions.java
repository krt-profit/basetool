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
 * Constants for the fine-grained permission strings a {@code Role} carries in its {@code
 * permissions} collection, read back as {@code GrantedAuthority} names without {@code ROLE_}.
 *
 * <p>Values must stay byte-identical to the {@code role_permissions} rows. {@code RoleService}
 * reads the {@code public static final String} fields reflectively as the auditable permission
 * vocabulary, so this class must hold only permission values.
 */
public final class Permissions {

  /** Non-instantiable static-constant holder. */
  private Permissions() {}

  public static final String HANGAR_READ = "HANGAR_READ";
  public static final String HANGAR_WRITE = "HANGAR_WRITE";
  public static final String MISSION_READ = "MISSION_READ";
  public static final String MISSION_WRITE = "MISSION_WRITE";
  public static final String MISSION_MANAGE = "MISSION_MANAGE";
  public static final String USER_MANAGE = "USER_MANAGE";
  public static final String ROLE_MANAGE = "ROLE_MANAGE";
}
