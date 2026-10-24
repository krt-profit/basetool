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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import org.hibernate.validator.constraints.URL;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * The internal realm URL the frontend's server-to-server Keycloak calls use instead of the public
 * issuer (REQ-SEC-087).
 *
 * <p>Blank keeps every call on the issuer and its discovery document. Set, the token, JWKS,
 * userinfo and health calls go to this URL, while the browser redirects and the {@code iss} check
 * stay on the public issuer.
 *
 * @param uri the realm base on the internal connector, e.g. {@code
 *     https://keycloak:18443/auth/realms/basetool}, or blank
 */
@Validated
@ConfigurationProperties(prefix = "app.security.keycloak-backchannel")
public record KeycloakBackchannelProperties(@Nullable @URL(protocol = "https") String uri) {

  /**
   * Whether a back-channel URL is configured.
   *
   * @return {@code true} when {@link #uri()} is not blank
   */
  public boolean enabled() {
    return StringUtils.hasText(uri);
  }

  /**
   * The configured realm base without a trailing slash.
   *
   * @return the trimmed URL, or {@code null} when none is configured
   */
  public @Nullable String realmBase() {
    return enabled() ? stripTrailingSlash(uri.trim()) : null;
  }

  /**
   * Removes one trailing slash, so a path appended to the result never doubles it.
   *
   * @param value the URL to normalise
   * @return the URL without a trailing slash
   */
  static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
