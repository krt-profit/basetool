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

package de.greluc.krt.profit.basetool.backend.scope.api;

import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDtoRedaction;
import de.greluc.krt.profit.basetool.backend.orgunit.api.CallerUserProjection;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The {@link CallerUserProjection} of the Basetool: admins and the owner of a record see it in
 * full, everyone else gets the peer shape (REQ-SEC-007).
 */
@Component
@RequiredArgsConstructor
public class AdminOrSelfUserProjection implements CallerUserProjection {

  private final AuthHelperService authHelperService;

  @Override
  public @NotNull UserDto forCaller(@NotNull UserDto full) {
    if (authHelperService.isAdmin()) {
      return full;
    }
    boolean self = authHelperService.currentUserId().filter(full.id()::equals).isPresent();
    return self ? full : UserDtoRedaction.toPeerShape(full);
  }
}
