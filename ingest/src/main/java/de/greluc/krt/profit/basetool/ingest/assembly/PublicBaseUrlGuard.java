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

package de.greluc.krt.profit.basetool.ingest.assembly;

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the gateway under the {@code prod} profile without {@code
 * app.ingest.public-base-url} (REQ-INGEST-012): without it the DPoP {@code htu} is compared with an
 * origin assembled from the request's {@code Host} and forwarded headers instead of a configured
 * one.
 */
@Component
@RequiredArgsConstructor
public class PublicBaseUrlGuard implements InitializingBean {

  /** The environment whose active profiles decide whether the guard applies. */
  private final @NotNull Environment environment;

  /** Holds the configured public origin. */
  private final @NotNull IngestProperties ingestProperties;

  /**
   * Checks the configuration once it is bound.
   *
   * @throws IllegalStateException when production runs without a public origin
   */
  @Override
  public void afterPropertiesSet() {
    check();
  }

  /**
   * Refuses a blank public origin under {@code prod}.
   *
   * @throws IllegalStateException when production runs without a public origin
   */
  void check() {
    if (environment.matchesProfiles("prod") && ingestProperties.publicBaseUrl().isBlank()) {
      throw new IllegalStateException(
          "app.ingest.public-base-url is empty under the prod profile; set"
              + " IRI_INGEST_PUBLIC_BASE_URL to the gateway's public origin, e.g."
              + " https://ingest.example.org (REQ-INGEST-012).");
    }
  }
}
