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

package de.greluc.krt.profit.basetool.frontend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;

/**
 * OIDC logout handler that ends the Keycloak session through the end-session endpoint only when the
 * {@link Authentication} holds an {@link OidcUser} with an ID token; otherwise it redirects
 * straight to the post-logout URL.
 */
@Slf4j
public class SmartOidcLogoutSuccessHandler extends SimpleUrlLogoutSuccessHandler {

  private final OidcClientInitiatedLogoutSuccessHandler oidcHandler;

  /**
   * Creates the handler.
   *
   * @param clientRegistrationRepository Keycloak client registry for resolving the end-session
   *     endpoint URL
   * @param postLogoutRedirectUri target URL after logout; may contain the {@code {baseUrl}}
   *     placeholder
   */
  public SmartOidcLogoutSuccessHandler(
      @NotNull ClientRegistrationRepository clientRegistrationRepository,
      @NotNull String postLogoutRedirectUri) {
    this.oidcHandler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
    this.oidcHandler.setPostLogoutRedirectUri(postLogoutRedirectUri);
    setDefaultTargetUrl("/");
  }

  @Override
  public void onLogoutSuccess(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      Authentication authentication)
      throws IOException, jakarta.servlet.ServletException {
    if (hasValidOidcToken(authentication)) {
      log.debug(
          "[Logout] Active OIDC session found – delegating to Keycloak end-session endpoint.");
      oidcHandler.onLogoutSuccess(request, response, authentication);
    } else {
      log.info(
          "[Logout] No active OIDC ID token found (session already expired) – skipping Keycloak"
              + " logout endpoint, redirecting directly to login page.");
      super.onLogoutSuccess(request, response, authentication);
    }
  }

  private boolean hasValidOidcToken(Authentication authentication) {
    if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
      return false;
    }
    if (!(oauthToken.getPrincipal() instanceof OidcUser oidcUser)) {
      return false;
    }
    return oidcUser.getIdToken() != null;
  }
}
