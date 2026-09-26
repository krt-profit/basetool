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

package de.greluc.krt.profit.basetool.ingest.config;

import jakarta.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.DPoPAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * Builds the DPoP {@code htu} comparison target from the configured public base URL instead of the
 * proxy-rewritten request URL (ADR-0129).
 *
 * <p>Only the origin is replaced; the path still comes from the request. Without a configured base
 * URL it delegates unchanged.
 */
public final class PublicUriDpopAuthenticationConverter implements AuthenticationConverter {

  private final DPoPAuthenticationConverter delegate = new DPoPAuthenticationConverter();
  private final String publicBaseUrl;

  /**
   * Creates the converter.
   *
   * @param publicBaseUrl the gateway's externally reachable origin (scheme, host and, only when
   *     non-default, port), without a trailing slash; blank keeps the stock request-derived target
   */
  public PublicUriDpopAuthenticationConverter(@NotNull String publicBaseUrl) {
    String trimmed = publicBaseUrl.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    this.publicBaseUrl = trimmed;
  }

  /**
   * Converts a DPoP-scheme request, substituting the configured origin into the {@code htu} target.
   *
   * @param request the current request
   * @return the DPoP authentication token, or {@code null} when this is not a DPoP request
   */
  @Override
  public @Nullable Authentication convert(@NotNull HttpServletRequest request) {
    Authentication authentication = delegate.convert(request);
    if (publicBaseUrl.isEmpty() || !(authentication instanceof DPoPAuthenticationToken token)) {
      return authentication;
    }
    return new DPoPAuthenticationToken(
        token.getAccessToken(),
        token.getDPoPProof(),
        token.getMethod(),
        publicBaseUrl + request.getRequestURI());
  }
}
