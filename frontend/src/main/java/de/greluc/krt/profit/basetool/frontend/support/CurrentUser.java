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

import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Reads the signed-in member's {@code app_user.id} from the OIDC principal (ADR-0142). The subject
 * is the user id; {@code authentication.getName()} is the username, not an id.
 */
public final class CurrentUser {

  /** Static-only holder. */
  private CurrentUser() {}

  /**
   * Returns the caller's {@code app_user.id} parsed as a UUID.
   *
   * @param principal the OIDC principal, or {@code null} when the request is anonymous
   * @return the caller's user id, or {@code null} when unauthenticated or not a UUID
   */
  @Nullable
  @Contract("null -> null")
  public static UUID userId(@Nullable OidcUser principal) {
    String raw = userIdText(principal);
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * Returns the caller's {@code app_user.id} as text, for template comparisons and string keys;
   * prefer {@link #userId(OidcUser)} for identifiers.
   *
   * @param principal the OIDC principal, or {@code null} when the request is anonymous
   * @return the caller's user id as text, or {@code null} when unauthenticated
   */
  @Nullable
  @Contract("null -> null")
  public static String userIdText(@Nullable OidcUser principal) {
    return principal == null ? null : principal.getSubject();
  }
}
