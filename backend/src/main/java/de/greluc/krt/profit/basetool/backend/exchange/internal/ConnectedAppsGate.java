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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Admits only the member's own browser session to the connected-apps controls, used as {@code
 * @connectedAppsGate} in {@code PreAuthorize} (REQ-XCH-001): never the gateway, an acting member
 * or the app.
 */
@Component("connectedAppsGate")
@RequiredArgsConstructor
public class ConnectedAppsGate {

  private final ConnectedAppsProperties properties;

  /**
   * Tells whether the caller is a member's own browser session.
   *
   * @param authentication the current authentication
   * @return {@code true} for a bearer issued to a configured web client, for a member
   */
  public boolean isMemberSession(@Nullable Authentication authentication) {
    if (authentication == null || authentication instanceof SubjectAuthentication) {
      return false;
    }
    return AuthenticatedSubject.idOf(authentication).isPresent()
        && AuthenticatedSubject.authorizedParty(authentication)
            .filter(properties.webClientIds()::contains)
            .isPresent();
  }
}
