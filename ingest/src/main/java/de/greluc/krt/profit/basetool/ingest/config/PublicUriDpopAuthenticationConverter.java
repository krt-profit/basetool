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
 * Builds the DPoP {@code htu} comparison target from a <em>configured</em> public base URL instead
 * of from the request (ADR-0129).
 *
 * <p>Spring compares {@code htu} with a bare {@code String.equals} — no normalisation, no
 * trailing-slash tolerance, no case folding — against {@code HttpServletRequest#getRequestURL()}.
 * Tomcat assembles that from scheme, server name and port, all of which {@code RemoteIpValve}
 * rewrites from the proxy's forwarded headers ({@code forward-headers-strategy: native}). The
 * client meanwhile signs a normalised URL it derives on its own. Both sides must land on a
 * byte-identical string, and the gateway's half depends on a reverse-proxy configuration that no
 * test exercises.
 *
 * <p>Concretely: if nginx-proxy-manager omits {@code X-Forwarded-Port}, the internal port survives
 * and the server expects {@code https://ingest.example:11262/v1/...} while the client signed {@code
 * https://ingest.example/v1/...}. One character, {@code invalid_dpop_proof}, and — with the stock
 * entry point — a bodyless 401 that never reaches this module's problem handler or its metrics. It
 * would pass every test and fail only in production, invisibly.
 *
 * <p>Pinning the origin to configuration removes the proxy from the comparison entirely: the value
 * is the same in dev, in CI and in production, and it is the value the extractor is documented to
 * sign. Only the origin is replaced; the path still comes from the request, so a proof remains
 * bound to the specific endpoint it was minted for.
 *
 * <p>With no base URL configured this delegates unchanged, so the stock behaviour is what an
 * unconfigured deployment gets rather than a half-applied override.
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
