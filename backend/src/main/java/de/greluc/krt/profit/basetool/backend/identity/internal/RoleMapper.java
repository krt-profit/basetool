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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.platform.api.CentralMapperConfig;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;

/** MapStruct mapper between Role entities and DTOs. */
@Mapper(config = CentralMapperConfig.class)
public interface RoleMapper {
  /** Maps a {@link Role} entity to its outbound DTO. */
  @Contract("null -> null; !null -> !null")
  default @Nullable RoleDto toDto(@Nullable Role role) {
    return role == null ? null : snapshot(role);
  }

  /**
   * Copies a role into an immutable cache-safe read model.
   *
   * @param role the managed role
   * @return the detached read model
   */
  static @NotNull RoleDto snapshot(@NotNull Role role) {
    return new RoleDto(
        role.getId(),
        role.getName(),
        role.getDescription(),
        role.getPermissions(),
        role.getVersion(),
        role.getDescriptionVersion(),
        role.getPermissionsVersion());
  }
}
