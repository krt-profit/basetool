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
 * Logs a concise startup banner as soon as the gateway's context is fully ready — the ingest twin
 * of the backend listener of the same name, so all three modules announce their effective runtime
 * configuration the same way.
 *
 * <p>The banner surfaces exactly the facts an on-call engineer reaches for when an ingest incident
 * starts: which profiles are live, which backend the relay forwards to, which frontend URL the
 * extractor is sent to, how long a handoff stays valid, which Keycloak issues the tokens being
 * validated, whether the per-IP throttle is armed, and the effective logging knobs. This is the
 * difference between reading a misconfiguration off the first ten log lines and inferring it from a
 * 502 an hour later.
 *
 * <p>No secret is ever printed: the Redis password, the keystore password and the monitoring-scrape
 * credentials are deliberately absent, and the Redis endpoint is rendered host:port only, with any
 * embedded credentials stripped by {@link #sanitiseRedisEndpoint(String, String)} (REQ-OBS-004).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupBannerListener {

  private final Environment environment;
  private final IngestProperties ingestProperties;
  private final LoggingProperties loggingProperties;
  private final RateLimitProperties rateLimitProperties;

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:unknown}")
  private String keycloakIssuerUri;

  @Value("${spring.application.name:ingest}")
  private String applicationName;

  @Value("${spring.data.redis.host:unknown}")
  private String redisHost;

  @Value("${spring.data.redis.port:}")
  private String redisPort;

  /**
   * Emits the startup banner once the application context is fully initialized. Triggered by {@link
   * ApplicationReadyEvent} so the {@code @ConfigurationProperties} beans and the resource-server
   * setup are wired and report real values rather than placeholders.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    log.info("============================================================");
    log.info(" Profit Basetool :: {} ready", applicationName);
    log.info(" Active profiles     : {}", Arrays.toString(environment.getActiveProfiles()));
    log.info(" Backend relay       : {}", ingestProperties.getBackendBaseUrl());
    log.info(" Frontend handoff    : {}", ingestProperties.getFrontendBaseUrl());
    log.info(" Handoff TTL         : {}", ingestProperties.getHandoffTtl());
    log.info(" Max payload (bytes) : {}", ingestProperties.getMaxPayloadBytes());
    log.info(" Redis staging       : {}", sanitiseRedisEndpoint(redisHost, redisPort));
    log.info(" Keycloak issuer     : {}", keycloakIssuerUri);
    log.info(" Per-IP rate limit   : {}", rateLimitProperties.isEnabled());
    log.info(" Correlation header  : {}", loggingProperties.correlationIdHeader());
    log.info(" Slow request (ms)   : {}", loggingProperties.slowRequestThresholdMs());
    log.info(" Structured logging  : {}", loggingProperties.structuredEnabled());
    log.info("============================================================");
  }

  /**
   * Renders the Redis endpoint as {@code host:port}, stripping anything before an {@code @} so a
   * {@code user:password@host}-style host value cannot leak a credential into the banner.
   *
   * @param host the configured Redis host, possibly carrying an inline {@code user:password@}
   *     prefix; {@code null} or blank yields {@code unknown}
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
