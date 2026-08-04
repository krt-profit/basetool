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
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The gateway's own Keycloak identity, used to call the backend under its own name instead of
 * relaying the caller's token (ADR-0129, REQ-INGEST-001).
 *
 * <p>Deliberately not validated as required. The gateway must still start when these are unset — an
 * operator who has not yet created the confidential client gets a gateway that refuses ingest writes
 * with a clear error, not a container that crash-loops and takes the health endpoints with it. The
 * emptiness is checked where it is used, once, with a named failure.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.ingest.service-account")
public class ServiceAccountProperties {

  /** Keycloak token endpoint the client-credentials grant is sent to. Empty disables the feature. */
  private String tokenUri = "";

  /** The gateway's confidential client id. Empty disables the feature. */
  private String clientId = "";

  /**
   * The gateway's client secret.
   *
   * <p>Never logged and never surfaced in a problem body: it is the credential that lets the gateway
   * act for any member, so it is the single most valuable secret in this module.
   */
  private String clientSecret = "";

  /**
   * How long before expiry a cached token is replaced.
   *
   * <p>Guards against handing the backend a token that expires in flight. Keycloak's access tokens
   * are minutes long, so this is a meaningful fraction of the lifetime rather than a rounding error.
   */
  private Duration refreshSkew = Duration.ofSeconds(30);

  /** How long to wait for the token endpoint before failing the ingest write. */
  @Min(1)
  private long timeoutMillis = 5_000;
}
