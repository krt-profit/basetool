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

package de.greluc.krt.profit.basetool.backend.identity.api;

import org.jetbrains.annotations.NotNull;

/**
 * Reduces a full {@link UserDto} or {@link UserReferenceDto} to what the current caller may see of
 * that member; implemented by the scope module so that the mapper and the user service do not
 * depend on the service layer (REQ-SEC-007).
 */
public interface CallerUserProjection {

  /**
   * Projects a user for the current caller.
   *
   * @param full the unredacted DTO
   * @return {@code full} for an admin and for the caller's own record, the peer shape otherwise
   */
  @NotNull
  UserDto forCaller(@NotNull UserDto full);

  /**
   * Projects a user reference for the current caller.
   *
   * @param full the unredacted reference
   * @return {@code full} for an admin and for the caller's own record, the peer reference otherwise
   */
  @NotNull
  UserReferenceDto forCaller(@NotNull UserReferenceDto full);
}
