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
 * The signed-in member's identity, read from the OIDC principal and named for what it is.
 *
 * <p>Nine controllers called {@code principal.getSubject()} at fifteen sites. The value is the
 * caller's {@code app_user.id} — the backend writes the row's primary key from the token's subject
 * at provisioning — but the call reads as "the sub", which is the identity provider's word for it,
 * and it sits one letter away from {@code authentication.getName()}, which returns the {@code
 * preferred_username}: <b>a name, not an id</b>. Three of those controllers carry comments warning
 * about exactly that confusion, all three written after it had already shipped a bug.
 *
 * <p>This is the "one helper, named for what it returns" of ADR-0142 point 2 (#1640). There is no
 * {@code getSubject} on it, because after this class there is nothing in the frontend that should
 * say "sub".
 */
public final class CurrentUser {

  /** Static-only holder. */
  private CurrentUser() {}

  /**
   * The caller's {@code app_user.id}, parsed.
   *
   * <p>Returns {@code null} for an absent principal and for a subject that is not a UUID rather
   * than throwing: every call site here decorates a page render, and a malformed subject must
   * degrade to "no self-highlighting" rather than to a 500. The backend refuses such a token
   * outright at its own seam, so this branch is a defensive floor, not a supported state.
   *
   * @param principal the OIDC principal, or {@code null} when the request is anonymous
   * @return the caller's user id, or {@code null} when unauthenticated or malformed
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
   * The caller's {@code app_user.id} in its rendered form.
   *
   * <p>For the model attributes a template compares against an id it received as JSON text, and for
   * the ingest handoff keys, which are strings by construction. Prefer {@link #userId(OidcUser)}
   * wherever the value is passed on as an identifier rather than printed.
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
