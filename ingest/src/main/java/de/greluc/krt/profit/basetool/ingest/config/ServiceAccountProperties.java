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
 * The gateway's own Keycloak identity, used to call the backend under its own name instead of
 * relaying the caller's token (ADR-0129, REQ-INGEST-001). Bound once at startup through the
 * canonical record constructor; nothing can change it afterwards.
 *
 * <p>Deliberately not validated as required. The gateway must still start when these are unset — an
 * operator who has not yet created the confidential client gets a gateway that refuses ingest
 * writes with a clear error, not a container that crash-loops and takes the health endpoints with
 * it. The emptiness is checked where it is used, once, with a named failure.
 *
 * <p>{@link #toString()} is overridden: the generated record form would print the client secret,
 * and a properties object is exactly what ends up in a bind-failure message or a debug line.
 *
 * @param tokenUri Keycloak token endpoint the client-credentials grant is sent to; empty disables
 *     the feature
 * @param clientId the gateway's confidential client id; empty disables the feature
 * @param clientSecret the gateway's client secret. Never logged and never surfaced in a problem
 *     body: it is the credential that lets the gateway act for any member, so it is the single most
 *     valuable secret in this module
 * @param refreshSkew how long before expiry a cached token is replaced. Guards against handing the
 *     backend a token that expires in flight; Keycloak's access tokens are minutes long, so this is
 *     a meaningful fraction of the lifetime rather than a rounding error
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
