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

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The gateway's own Keycloak identity for calling the backend under its own name (ADR-0129,
 * REQ-INGEST-001).
 *
 * <p>Not required at startup: when unset, ingest writes fail with a clear error. {@link
 * #toString()} omits the client secret.
 *
 * @param tokenUri Keycloak token endpoint for the client-credentials grant; empty disables the
 *     feature
 * @param clientId the gateway's confidential client id; empty disables the feature
 * @param clientSecret the gateway's client secret; never logged or exposed
 * @param refreshSkew how long before expiry a cached token is replaced
 * @param timeoutMillis how long to wait for the token endpoint before failing the ingest write
 */
@Validated
@ConfigurationProperties(prefix = "app.ingest.service-account")
public record ServiceAccountProperties(
    @DefaultValue("") String tokenUri,
    @DefaultValue("") String clientId,
    @DefaultValue("") String clientSecret,
    @DefaultValue("30s") Duration refreshSkew,
    @Min(1) @DefaultValue("5000") long timeoutMillis) {

  /**
   * Normalises absent values so every consumer can call {@code isBlank()} without a null check: a
   * {@code null} string becomes empty (feature off) and a {@code null} skew becomes the 30-second
   * default.
   */
  public ServiceAccountProperties {
    tokenUri = tokenUri == null ? "" : tokenUri;
    clientId = clientId == null ? "" : clientId;
    clientSecret = clientSecret == null ? "" : clientSecret;
    refreshSkew = refreshSkew == null ? Duration.ofSeconds(30) : refreshSkew;
  }

  /**
   * Renders the configuration with the client secret masked, so the record can never leak the
   * credential through a log line or a bind-failure message.
   *
   * @return the token URI, client id, whether a secret is set, the skew and the timeout
   */
  @Override
  public @NotNull String toString() {
    return "ServiceAccountProperties[tokenUri="
        + tokenUri
        + ", clientId="
        + clientId
        + ", clientSecret="
        + (clientSecret.isBlank() ? "<unset>" : "<set>")
        + ", refreshSkew="
        + refreshSkew
        + ", timeoutMillis="
        + timeoutMillis
        + "]";
  }
}
