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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding tests for {@code app.logging.*}. The defaults matter because the gateway ships without
 * the block set in every profile, and the validation matters because a blank MDC key would silently
 * break the {@code %X{...}} rendering in {@code logback-spring.xml} — the kind of misconfiguration
 * that must abort the boot rather than surface as log lines with a missing correlation id.
 */
class LoggingPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(LoggingProperties.class)
  static class TestConfig {}

  @Test
  void bindsTheDocumentedDefaultsWhenNothingIsConfigured() {
    runner.run(
        context -> {
          LoggingProperties properties = context.getBean(LoggingProperties.class);
          assertThat(properties.correlationIdHeader()).isEqualTo("X-Correlation-Id");
          assertThat(properties.correlationIdMdcKey()).isEqualTo("correlationId");
          assertThat(properties.userIdMdcKey()).isEqualTo("userId");
          assertThat(properties.slowRequestThresholdMs()).isEqualTo(2000L);
          assertThat(properties.slowBackendCallThresholdMs()).isEqualTo(1500L);
          assertThat(properties.structuredEnabled()).isFalse();
        });
  }

  @Test
  void bindsOverriddenValues() {
    runner
        .withPropertyValues(
            "app.logging.correlation-id-header=X-Trace",
            "app.logging.slow-request-threshold-ms=50",
            "app.logging.slow-backend-call-threshold-ms=25",
            "app.logging.structured-enabled=true")
        .run(
            context -> {
              LoggingProperties properties = context.getBean(LoggingProperties.class);
              assertThat(properties.correlationIdHeader()).isEqualTo("X-Trace");
              assertThat(properties.slowRequestThresholdMs()).isEqualTo(50L);
              assertThat(properties.slowBackendCallThresholdMs()).isEqualTo(25L);
              assertThat(properties.structuredEnabled()).isTrue();
            });
  }

  @Test
  void refusesToStartOnABlankCorrelationHeader() {
    runner
        .withPropertyValues("app.logging.correlation-id-header=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void refusesToStartOnABlankMdcKey() {
    runner
        .withPropertyValues("app.logging.user-id-mdc-key=  ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void refusesToStartOnANegativeThreshold() {
    runner
        .withPropertyValues("app.logging.slow-request-threshold-ms=-1")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void keepsTheMdcKeysInSyncWithTheLogbackPattern() {
    // logback-spring.xml renders %X{correlationId} and %X{userId:-anonymous}; a renamed key here
    // would leave both fields permanently blank in every log line without any error.
    runner.run(
        context -> {
          LoggingProperties properties = context.getBean(LoggingProperties.class);
          assertThat(properties.correlationIdMdcKey()).isEqualTo("correlationId");
          assertThat(properties.userIdMdcKey()).isEqualTo("userId");
        });
  }
}
