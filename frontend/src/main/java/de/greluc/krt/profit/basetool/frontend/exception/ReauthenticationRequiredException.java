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

package de.greluc.krt.profit.basetool.frontend.exception;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.oauth2.client.ClientAuthorizationException;

/**
 * Raised when a frontend-to-backend call cannot proceed because the user's session no longer holds
 * a usable OAuth2 token for the {@code keycloak} client registration.
 *
 * <p>Thrown locally, before any backend request is sent, when {@code BackendApiClient} detects a
 * {@link ClientAuthorizationException} (see {@link #isReauthSignal(Throwable)}). {@code
 * GlobalExceptionHandler} maps it to a redirect to {@link #REAUTH_PATH} for HTML navigations or a
 * {@code 401} with {@code X-Reauthenticate} for AJAX callers (REQ-SEC-012).
 */
public class ReauthenticationRequiredException extends RuntimeException {

  /**
   * Same-origin path that (re-)initiates the Keycloak authorization-code login flow. Hitting it
   * mints a fresh {@code OAuth2AuthorizedClient}; while the Keycloak SSO session is still alive the
   * re-authentication is transparent (no visible login prompt).
   */
  public static final String REAUTH_PATH = "/oauth2/authorization/keycloak";

  /** Upper bound on cause-chain traversal in {@link #isReauthSignal(Throwable)} (loop guard). */
  private static final int MAX_CAUSE_DEPTH = 25;

  /**
   * Creates the exception.
   *
   * @param message a developer-facing description of the failed call (never user-facing)
   * @param cause the originating {@link ClientAuthorizationException} (or a wrapper of it)
   */
  public ReauthenticationRequiredException(@NotNull String message, @Nullable Throwable cause) {
    super(message, cause);
  }

  /**
   * Reports whether {@code throwable} — or any exception in its cause chain — is a Spring Security
   * {@link ClientAuthorizationException}, which signals that the frontend OAuth2 client cannot
   * produce a usable token and the user must re-authenticate. The {@code
   * ClientAuthorizationRequiredException} subtype (no usable client) and the refresh-token-grant
   * failures it wraps ({@code invalid_grant} after a revoked / rotated refresh token) are both
   * covered, since both are thrown as {@link ClientAuthorizationException} instances.
   *
   * <p>The traversal is bounded by {@link #MAX_CAUSE_DEPTH} and tolerates a self-referential cause
   * chain ({@code cause == this}) so a pathological throwable cannot spin forever.
   *
   * @param throwable the throwable to inspect; may be {@code null}
   * @return {@code true} if a {@link ClientAuthorizationException} is found in the chain
   */
  @Contract("null -> false")
  public static boolean isReauthSignal(@Nullable Throwable throwable) {
    Throwable current = throwable;
    int depth = 0;
    while (current != null && depth++ < MAX_CAUSE_DEPTH) {
      if (current instanceof ClientAuthorizationException) {
        return true;
      }
      Throwable next = current.getCause();
      if (next == current) {
        break;
      }
      current = next;
    }
    return false;
  }
}
