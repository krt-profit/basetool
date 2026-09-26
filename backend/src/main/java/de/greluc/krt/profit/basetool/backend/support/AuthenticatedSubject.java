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

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Answers which subject a request is acting as, for token-based and token-less (ADR-0129)
 * authentications alike.
 *
 * <p>Never falls back to {@code getName()}, which is the member's callsign on a username/password
 * authentication (REQ-OBS-004); a token-less authentication opts in via {@link
 * SubjectAuthentication}. Does not gate on {@code isAuthenticated()}, which a single-argument
 * {@code JwtAuthenticationToken} leaves false.
 */
public final class AuthenticatedSubject {

  /** Authorized-party claim: the Keycloak client id a token was issued to (OIDC Core section 2). */
  private static final String AUTHORIZED_PARTY_CLAIM = "azp";

  private AuthenticatedSubject() {}

  /**
   * Extracts the acting subject from a token's {@code sub} or from a {@link SubjectAuthentication},
   * never from {@link Authentication#getName()}.
   *
   * @param authentication the current authentication, may be {@code null}
   * @return the subject, or empty when there is no authenticated caller
   */
  public static Optional<String> of(@Nullable Authentication authentication) {
    if (authentication == null) {
      return Optional.empty();
    }
    if (authentication instanceof JwtAuthenticationToken jwtAuth && jwtAuth.getToken() != null) {
      return Optional.ofNullable(jwtAuth.getToken().getSubject()).filter(s -> !s.isBlank());
    }
    if (authentication.getPrincipal() instanceof Jwt jwt) {
      return Optional.ofNullable(jwt.getSubject()).filter(s -> !s.isBlank());
    }
    if (authentication instanceof SubjectAuthentication subjectAuth) {
      return Optional.ofNullable(subjectAuth.subject()).filter(s -> !s.isBlank());
    }
    return Optional.empty();
  }

  /**
   * Extracts the unbounded {@code azp} claim naming the Keycloak client the caller's token was
   * issued to. Consumers that label or persist it must bound it via {@link ClientAttribution}.
   *
   * @param authentication the current authentication, may be {@code null}
   * @return the {@code azp} claim, or empty when there is no token or the claim is absent/blank
   */
  public static Optional<String> authorizedParty(@Nullable Authentication authentication) {
    return token(authentication)
        .map(jwt -> jwt.getClaimAsString(AUTHORIZED_PARTY_CLAIM))
        .filter(azp -> !azp.isBlank());
  }

  /**
   * The caller's token, from either shape Spring Security presents it in.
   *
   * @param authentication the current authentication, may be {@code null}
   * @return the token, or empty for a token-less or absent authentication
   */
  @NotNull
  private static Optional<Jwt> token(@Nullable Authentication authentication) {
    if (authentication == null) {
      return Optional.empty();
    }
    if (authentication instanceof JwtAuthenticationToken jwtAuth) {
      return Optional.ofNullable(jwtAuth.getToken());
    }
    return authentication.getPrincipal() instanceof Jwt jwt ? Optional.of(jwt) : Optional.empty();
  }

  /**
   * Returns {@link #of(Authentication)} as a {@link UUID}, empty for a non-UUID subject.
   *
   * @param authentication the current authentication, may be {@code null}
   * @return the subject as a UUID, or empty when absent or not a UUID
   */
  public static Optional<UUID> idOf(@Nullable Authentication authentication) {
    return of(authentication)
        .flatMap(
            subject -> {
              try {
                return Optional.of(UUID.fromString(subject));
              } catch (IllegalArgumentException notAnId) {
                return Optional.empty();
              }
            });
  }
}
