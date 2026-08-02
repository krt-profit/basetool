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
 * Central constant holder for the fine-grained permission strings a {@code Role} entity carries in
 * its {@code permissions} collection (S3, part of #905), replacing raw string literals in {@code
 * DataInitializer} and the {@code hasAuthority}/{@code hasAnyAuthority} URL matchers in {@code
 * SecurityConfig}.
 *
 * <p>Values are <b>byte-identical</b> to the existing {@code role_permissions} DB rows and must
 * stay that way — a changed constant here silently revokes/grants a permission for every role that
 * carries it. Unlike {@link Roles}, these are not Keycloak-realm concepts: they exist only as
 * strings in the {@code Role.permissions} {@code ElementCollection} and are read back as plain
 * Spring {@code GrantedAuthority} names (no {@code ROLE_} prefix).
 *
 * <p><b>This holder is read reflectively.</b> {@code RoleService} derives the vocabulary its {@code
 * ROLE_PERMISSIONS_CHANGED} audit row and log line may name from the {@code public static final
 * String} fields declared here, so a permission added below is audited by name from the moment it
 * exists — no second list to update. The price is that this class must keep holding <b>only</b>
 * permission values: a {@code String} constant of any other meaning added here would silently
 * become an auditable permission name.
 *
 * <p>Lives in the dependency-leaf {@code support} package (ADR-0047) — plain {@code String}
 * constants with no dependency on the security API, {@code model} or {@code service}.
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
