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

package de.greluc.krt.profit.basetool.ingest.logging;

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.config.RateLimitProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.IngestGatePostureMetric;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Logs a startup banner with the gateway's effective configuration: profiles, backend and frontend
 * URLs, handoff lifetime, issuer, throttles, client gates (REQ-INGEST-011) and logging settings.
 *
 * <p>No secret is printed; the Redis endpoint is sanitised by {@link #sanitiseRedisEndpoint(String,
 * String)} (REQ-OBS-004).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupBannerListener {

  private final Environment environment;
  private final IngestProperties ingestProperties;
  private final LoggingProperties loggingProperties;
  private final RateLimitProperties rateLimitProperties;

  /**
   * Supplies the client-gate posture line — booleans and list sizes only, never a configured client
   * id, scope, tool or audience (REQ-OBS-004).
   */
  private final IngestGatePostureMetric gatePostureMetric;

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:unknown}")
  private String keycloakIssuerUri;

  @Value("${spring.application.name:ingest}")
  private String applicationName;

  @Value("${spring.data.redis.host:unknown}")
  private String redisHost;

  @Value("${spring.data.redis.port:}")
  private String redisPort;

  /** Emits the startup banner once the application context is fully ready. */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    log.info("============================================================");
    log.info(" Profit Basetool :: {} ready", applicationName);
    log.info(" Active profiles     : {}", Arrays.toString(environment.getActiveProfiles()));
    log.info(" Backend relay       : {}", ingestProperties.backendBaseUrl());
    log.info(" Frontend handoff    : {}", ingestProperties.frontendBaseUrl());
    log.info(" Handoff TTL         : {}", ingestProperties.handoffTtl());
    log.info(" Max payload (bytes) : {}", ingestProperties.maxPayloadBytes());
    log.info(" Redis staging       : {}", sanitiseRedisEndpoint(redisHost, redisPort));
    log.info(" Keycloak issuer     : {}", keycloakIssuerUri);
    log.info(
        " Rate limits         : {} (subject {}/{}, ip {}/{})",
        rateLimitProperties.enabled(),
        rateLimitProperties.capacity(),
        rateLimitProperties.refillPeriod(),
        rateLimitProperties.ipCapacity(),
        rateLimitProperties.refillPeriod());
    log.info(" Client gates        : {}", gatePostureMetric.posture().describe());
    log.info(" Correlation header  : {}", loggingProperties.correlationIdHeader());
    log.info(" Slow request (ms)   : {}", loggingProperties.slowRequestThresholdMs());
    log.info(" Structured logging  : {}", loggingProperties.structuredEnabled());
    log.info("============================================================");
  }

  /**
   * Renders the Redis endpoint as {@code host:port}, dropping any {@code user:password@} prefix.
   *
   * @param host the configured Redis host; {@code null} or blank yields {@code unknown}
   * @param port the configured Redis port; {@code null} or blank renders the host alone
   * @return the sanitised {@code host} or {@code host:port} string
   */
  @NotNull
  static String sanitiseRedisEndpoint(@Nullable String host, @Nullable String port) {
    if (host == null || host.isBlank()) {
      return "unknown";
    }
    int at = host.lastIndexOf('@');
    String sanitisedHost = at >= 0 ? "***@" + host.substring(at + 1) : host;
    return port == null || port.isBlank() ? sanitisedHost : sanitisedHost + ":" + port;
  }
}
