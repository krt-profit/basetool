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
 * Authorized-client repository that stores clients without their registration's secret and restores
 * the current {@link ClientRegistration} on every read (REQ-SEC-069, ADR-0001).
 *
 * <p>This keeps the client secret out of Redis sessions and makes a changed client type or rotated
 * secret apply to existing sessions immediately. Principal and tokens are returned as stored.
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
   * @param clientRegistrationId the registration id
   * @param principal the authenticated principal
   * @param request the current request
   * @param <T> the expected client type; only plain {@link OAuth2AuthorizedClient}s are stored
   * @return the stored client with the current registration, unchanged when the registration id is
   *     unknown, or {@code null} when nothing is stored
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
