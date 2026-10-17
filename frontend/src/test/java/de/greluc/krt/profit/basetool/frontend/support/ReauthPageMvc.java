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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Builds the standalone {@link MockMvc} that proves a page GET with an expired session redirects to
 * the Keycloak login instead of rendering its section fallbacks (REQ-FE-036, REQ-SEC-012).
 */
public final class ReauthPageMvc {

  private ReauthPageMvc() {}

  /**
   * Wraps the controllers with the real {@link GlobalExceptionHandler} and the {@code
   * AuthenticationPrincipal} resolver.
   *
   * @param controllers the controllers under test
   * @return the MockMvc
   */
  @NotNull
  public static MockMvc standalone(@NotNull Object... controllers) {
    return MockMvcBuilders.standaloneSetup(controllers)
        .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .build();
  }

  /**
   * Signs a member with the given roles into the security context of the current thread; the test
   * clears it with {@link SecurityContextHolder#clearContext()}.
   *
   * @param roles the role names without the {@code ROLE_} prefix
   */
  public static void signInMember(@NotNull String... roles) {
    OidcIdToken idToken =
        OidcIdToken.withTokenValue("token")
            .subject("member-sub")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("preferred_username", "member")
            .build();
    List<GrantedAuthority> authorities =
        Arrays.stream(roles)
            .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
    DefaultOidcUser user = new DefaultOidcUser(authorities, idToken);
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(user, authorities, "keycloak"));
  }

  /**
   * Creates the failure the backend seam throws when the session lost its OAuth2 token.
   *
   * @return the failure
   */
  @NotNull
  public static ReauthenticationRequiredException expiredSession() {
    return new ReauthenticationRequiredException(
        "expired",
        new ClientAuthorizationException(new OAuth2Error("invalid_grant"), "keycloak"));
  }

  /**
   * Matches the redirect the global handler answers a navigation with a lost session.
   *
   * @return the matcher
   */
  @NotNull
  public static ResultMatcher redirectsToLogin() {
    return redirectedUrl(ReauthenticationRequiredException.REAUTH_PATH);
  }
}
