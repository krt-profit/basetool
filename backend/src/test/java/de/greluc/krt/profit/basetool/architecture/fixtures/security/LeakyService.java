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

package de.greluc.krt.profit.basetool.architecture.fixtures.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/** Planted violation: a service that reads the security context and tests the token type. */
@Service
public class LeakyService {

  /**
   * Reads the principal straight from the security context.
   *
   * @return the context
   */
  public Object principal() {
    return SecurityContextHolder.getContext();
  }

  /**
   * Asks for the authentication type instead of the subject.
   *
   * @param authentication the authentication
   * @return whether it is a JWT
   */
  public boolean isJwt(Object authentication) {
    return authentication instanceof JwtAuthenticationToken;
  }
}
