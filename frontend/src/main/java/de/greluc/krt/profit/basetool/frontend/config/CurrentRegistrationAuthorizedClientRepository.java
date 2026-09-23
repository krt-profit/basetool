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
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Keeps the client registration <em>out</em> of the stored authorized client and puts the
 * <em>current</em> one back on every read (REQ-SEC-069, ADR-0001).
 *
 * <p>The session-backed repository stores the whole {@link OAuth2AuthorizedClient} in Redis, and
 * that object embeds the {@link ClientRegistration} it was issued under — client secret and
 * authentication method included. Two things follow, and this class exists for both:
 *
 * <ol>
 *   <li><strong>The secret would sit in every session.</strong> A confidential client's secret, in
 *       clear text, in every member's session hash and in every Redis snapshot. On save the
 *       registration is stored with an empty secret instead.
 *   <li><strong>A session outlives the registration it was stored with.</strong> Spring Security
 *       refreshes a token with the registration <em>inside</em> the stored client. A session
 *       created while the frontend was public would therefore refresh as a public client for its
 *       whole 30-day life — against a Keycloak that, after the rollout, requires the secret: {@code
 *       invalid_client} at the next refresh, and every member sent back through the login. On load
 *       the stored registration is replaced by the one the application runs with now, so a switch
 *       of client type, or a rotated secret, applies to existing sessions at once.
 * </ol>
 *
 * <p>Only the registration is swapped. Principal, access token and refresh token are the stored
 * ones, and a registration id the repository no longer knows is returned as stored.
 */
@RequiredArgsConstructor
public class CurrentRegistrationAuthorizedClientRepository
    implements OAuth2AuthorizedClientRepository {

  /** The repository that actually stores the client (the HTTP session, in production). */
  private final @NotNull OAuth2AuthorizedClientRepository delegate;

  /** The registrations the application runs with now. */
  private final @NotNull ClientRegistrationRepository registrations;

  /**
   * Loads the stored client and gives it the current registration.
   *
   * @param clientRegistrationId the registration id.
   * @param principal the authenticated principal.
   * @param request the current request.
   * @param <T> the client type the caller expects; this repository only ever stores plain {@link
   *     OAuth2AuthorizedClient}s.
   * @return the stored client carrying the current registration, the stored client unchanged when
   *     the registration id is unknown now, or {@code null} when nothing is stored.
   */
  @Override
  public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
      String clientRegistrationId, Authentication principal, HttpServletRequest request) {
    T stored = delegate.loadAuthorizedClient(clientRegistrationId, principal, request);
    if (stored == null) {
      return null;
    }
    ClientRegistration current = registrations.findByRegistrationId(clientRegistrationId);
    if (current == null) {
      return stored;
    }
    // The interface is generic in the return type only so a subclass of OAuth2AuthorizedClient
    // can be stored; this application stores the plain class, which is what is rebuilt here, so
    // the unchecked cast narrows nothing it could get wrong.
    @SuppressWarnings("unchecked")
    T rebuilt =
        (T)
            new OAuth2AuthorizedClient(
                current,
                stored.getPrincipalName(),
                stored.getAccessToken(),
                stored.getRefreshToken());
    return rebuilt;
  }

  /**
   * Stores the client with its registration's secret removed.
   *
   * @param authorizedClient the client to store.
   * @param principal the authenticated principal.
   * @param request the current request.
   * @param response the current response.
   */
  @Override
  public void saveAuthorizedClient(
      @NotNull OAuth2AuthorizedClient authorizedClient,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    ClientRegistration withoutSecret =
        ClientRegistration.withClientRegistration(authorizedClient.getClientRegistration())
            .clientSecret(null)
            .build();
    delegate.saveAuthorizedClient(
        new OAuth2AuthorizedClient(
            withoutSecret,
            authorizedClient.getPrincipalName(),
            authorizedClient.getAccessToken(),
            authorizedClient.getRefreshToken()),
        principal,
        request,
        response);
  }

  /**
   * Removes the stored client.
   *
   * @param clientRegistrationId the registration id.
   * @param principal the authenticated principal.
   * @param request the current request.
   * @param response the current response.
   */
  @Override
  public void removeAuthorizedClient(
      String clientRegistrationId,
      Authentication principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    delegate.removeAuthorizedClient(clientRegistrationId, principal, request, response);
  }
}
