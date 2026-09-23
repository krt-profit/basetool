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

package de.greluc.krt.profit.basetool.backend.logging;

import de.greluc.krt.profit.basetool.backend.config.LoggingProperties;
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
 * Logs a concise startup banner as soon as the application context is fully ready.
 *
 * <p>The banner surfaces the most important runtime facts a developer or on-call engineer needs
 * when triaging an incident:
 *
 * <ul>
 *   <li>active Spring profiles
 *   <li>Keycloak issuer URI (public, no secret)
 *   <li>Datasource URL with credentials <b>stripped</b>
 *   <li>effective logging configuration (correlation header, slow-request threshold, structured)
 * </ul>
 *
 * <p>Secrets like {@code spring.datasource.password}, admin client secrets and tokens are never
 * logged. JDBC URLs are sanitised via {@link #sanitiseJdbcUrl(String)} to remove any {@code
 * user=}/{@code password=} query parameters that some drivers accept.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupBannerListener {

  private final Environment environment;
  private final LoggingProperties loggingProperties;

  @Value("${spring.datasource.url:unknown}")
  private String datasourceUrl;

  @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:unknown}")
  private String keycloakIssuerUri;

  @Value("${spring.application.name:backend}")
  private String applicationName;

  /**
   * Emits the startup banner once the application context is fully initialized. Triggered by {@link
   * ApplicationReadyEvent} so {@code @ConfigurationProperties}, datasource and security subsystems
   * are all wired up and produce real values rather than placeholders.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    log.info("============================================================");
    log.info(" Profit Basetool :: {} ready", applicationName);
    log.info(" Active profiles     : {}", Arrays.toString(environment.getActiveProfiles()));
    log.info(" Datasource URL      : {}", sanitiseJdbcUrl(datasourceUrl));
    log.info(" Keycloak issuer     : {}", keycloakIssuerUri);
    log.info(" Correlation header  : {}", loggingProperties.correlationIdHeader());
    log.info(" Slow request (ms)   : {}", loggingProperties.slowRequestThresholdMs());
    log.info(" Structured logging  : {}", loggingProperties.structuredEnabled());
    log.info("============================================================");
  }

  /**
   * Removes user/password fragments from a JDBC URL. Handles both {@code
   * jdbc:postgresql://host:port/db?user=x&password=y} and the occasional {@code
   * jdbc:postgresql://user:pw@host/db}.
   */
  @NotNull
  static String sanitiseJdbcUrl(@Nullable String url) {
    if (url == null || url.isBlank()) {
      return "unknown";
    }
    String sanitised = url.replaceAll("(?i)([?&])(user|password)=[^&]*", "$1$2=***");
    sanitised = sanitised.replaceAll("(?i)(jdbc:[^/]+://)([^/@]+)@", "$1***@");
    return sanitised;
  }
}
