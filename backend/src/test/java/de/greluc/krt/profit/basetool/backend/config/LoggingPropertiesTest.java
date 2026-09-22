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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding contract for {@link LoggingProperties}.
 *
 * <p>Defaults must be stable (used by logback-spring.xml pattern) and validation must fail fast on
 * invalid values so the context never starts with a broken logging configuration.
 */
class LoggingPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
          .withUserConfiguration(Config.class);

  @Configuration
  @EnableConfigurationProperties(LoggingProperties.class)
  @ConfigurationPropertiesScan
  static class Config {}

  @Test
  void defaults_ShouldMatchLogbackPatternExpectations() {
    runner.run(
        context -> {
          LoggingProperties props = context.getBean(LoggingProperties.class);

          // Given/When: defaults loaded
          // Then: match the %X{correlationId}/%X{userId} placeholders in logback-spring.xml
          assertThat(props.correlationIdHeader()).isEqualTo("X-Correlation-Id");
          assertThat(props.correlationIdMdcKey()).isEqualTo("correlationId");
          assertThat(props.userIdMdcKey()).isEqualTo("userId");
          assertThat(props.slowRequestThresholdMs()).isEqualTo(2000L);
          assertThat(props.structuredEnabled()).isFalse();
        });
  }

  @Test
  void invalidSlowRequestThreshold_ShouldFailContextStart() {
    runner
        .withPropertyValues("app.logging.slow-request-threshold-ms=-1")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void blankCorrelationHeader_ShouldFailContextStart() {
    runner
        .withPropertyValues("app.logging.correlation-id-header=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void customValues_ShouldOverrideDefaults() {
    runner
        .withPropertyValues(
            "app.logging.correlation-id-header=X-Trace-Id",
            "app.logging.slow-request-threshold-ms=5000",
            "app.logging.structured-enabled=true")
        .run(
            context -> {
              LoggingProperties p = context.getBean(LoggingProperties.class);
              assertThat(p.correlationIdHeader()).isEqualTo("X-Trace-Id");
              assertThat(p.slowRequestThresholdMs()).isEqualTo(5000L);
              assertThat(p.structuredEnabled()).isTrue();
            });
  }

  @Test
  void toString_ShouldNotLeakSensitiveInformation() {
    LoggingProperties p = BoundProperties.defaults(LoggingProperties.class);
    // Given/When
    String s = p.toString();
    // Then: deterministic content, no password/token-like fields
    assertThat(s).contains("correlationIdHeader", "slowRequestThresholdMs");
    assertThat(s).doesNotContain("password", "token", "secret");
  }

  /**
   * BE-MOD-04: the properties are an immutable record bound through its canonical constructor, and
   * a key left unset keeps its {@code @DefaultValue}.
   */
  @Test
  void binding_ShouldGoThroughTheRecordConstructor() {
    LoggingProperties p =
        BoundProperties.bind(LoggingProperties.class, "slow-request-threshold-ms", 500);

    assertThat(LoggingProperties.class.isRecord()).isTrue();
    assertThat(p.slowRequestThresholdMs()).isEqualTo(500L);
    assertThat(p.correlationIdHeader()).isEqualTo("X-Correlation-Id");
    assertThat(p.orgUnitIdMdcKey()).isEqualTo("orgUnitId");
  }
}
