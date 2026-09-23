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

import de.greluc.krt.profit.basetool.backend.support.AuditRetentionProperties;
import de.greluc.krt.profit.basetool.backend.support.AuthoritiesCacheProperties;
import de.greluc.krt.profit.basetool.backend.support.NotificationRetentionProperties;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import de.greluc.krt.profit.basetool.backend.support.RejectedRegistrationRetentionProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Startup-validation tests for the backend's {@code @ConfigurationProperties} classes.
 *
 * <p><strong>One properties class per runner, deliberately.</strong> A runner that registers
 * several of them cannot assert a <em>successful</em> start: {@link KeycloakSyncProperties} alone
 * carries four {@code @NotBlank} fields with no defaults ({@code adminUrl}, {@code realm}, {@code
 * clientId}, {@code clientSecret}), so any context that does not supply them fails binding whatever
 * the class under test does. The subtler damage is to the failure cases — a shared runner makes
 * {@code hasFailed()} pass for the wrong reason, so such a test stays green even with its own
 * constraint deleted. Isolating each class keeps every assertion about its own subject.
 */
class BackendPropertiesValidationTest {

  private final ApplicationContextRunner rateLimitRunner = runnerFor(RateLimitConfig.class);
  private final ApplicationContextRunner keycloakSyncRunner = runnerFor(KeycloakSyncConfig.class);
  private final ApplicationContextRunner authoritiesCacheRunner =
      runnerFor(AuthoritiesCacheConfig.class);
  private final ApplicationContextRunner auditRetentionRunner =
      runnerFor(AuditRetentionConfig.class);
  private final ApplicationContextRunner notificationRetentionRunner =
      runnerFor(NotificationRetentionConfig.class);
  private final ApplicationContextRunner rejectedRetentionRunner =
      runnerFor(RejectedRegistrationRetentionConfig.class);

  /**
   * Builds a context runner around one properties configuration and a real JSR-380 validator.
   *
   * @param configuration a nested {@code @Configuration} enabling exactly one properties class
   * @return a runner whose only binding subject is that class
   */
  private static ApplicationContextRunner runnerFor(Class<?> configuration) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(configuration);
  }

  /** Registers {@link RateLimitProperties} and the validator that enforces its constraints. */
  @Configuration
  @EnableConfigurationProperties(RateLimitProperties.class)
  static class RateLimitConfig {
    /**
     * The JSR-380 validator {@code @Validated} properties binding delegates to.
     *
     * @return a real validator factory, so constraint violations fail the context as in production
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  /** Registers {@link KeycloakSyncProperties} and the validator that enforces its constraints. */
  @Configuration
  @EnableConfigurationProperties(KeycloakSyncProperties.class)
  static class KeycloakSyncConfig {
    /**
     * The JSR-380 validator {@code @Validated} properties binding delegates to.
     *
     * @return a real validator factory, so constraint violations fail the context as in production
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  /** Registers {@link AuthoritiesCacheProperties} and the validator enforcing its TTL bounds. */
  @Configuration
  @EnableConfigurationProperties(AuthoritiesCacheProperties.class)
  static class AuthoritiesCacheConfig {
    /**
     * The JSR-380 validator {@code @Validated} properties binding delegates to.
     *
     * @return a real validator factory, so constraint violations fail the context as in production
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  /** Registers {@link AuditRetentionProperties} and the validator enforcing its floors. */
  @Configuration
  @EnableConfigurationProperties(AuditRetentionProperties.class)
  static class AuditRetentionConfig {
    /**
     * The JSR-380 validator {@code @Validated} properties binding delegates to.
     *
     * @return a real validator factory, so constraint violations fail the context as in production
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  /** Registers {@link NotificationRetentionProperties} and the validator enforcing its floors. */
  @Configuration
  @EnableConfigurationProperties(NotificationRetentionProperties.class)
  static class NotificationRetentionConfig {
    /**
     * The JSR-380 validator {@code @Validated} properties binding delegates to.
     *
     * @return a real validator factory, so constraint violations fail the context as in production
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  /**
   * Registers {@link RejectedRegistrationRetentionProperties} and the validator enforcing its
   * floor.
   */
  @Configuration
  @EnableConfigurationProperties(RejectedRegistrationRetentionProperties.class)
  static class RejectedRegistrationRetentionConfig {
    /**
     * The JSR-380 validator {@code @Validated} properties binding delegates to.
     *
     * @return a real validator factory, so constraint violations fail the context as in production
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  @Test
  void shouldFail_WhenRateLimitCapacityIsZero() {
    rateLimitRunner
        .withPropertyValues(
            "app.rate-limit.capacity=0",
            "app.rate-limit.refillTokens=1",
            "app.rate-limit.refillPeriod=1m")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** The same properties minus the offending one must start, or the test above proves nothing. */
  @Test
  void shouldStart_WhenRateLimitCapacityIsValid() {
    rateLimitRunner
        .withPropertyValues(
            "app.rate-limit.capacity=1",
            "app.rate-limit.refillTokens=1",
            "app.rate-limit.refillPeriod=1m")
        .run((context) -> assertThat(context).hasNotFailed());
  }

  @Test
  void shouldFail_WhenKeycloakAdminUrlInvalid() {
    keycloakSyncRunner
        .withPropertyValues(
            "app.keycloak.sync.enabled=true",
            "app.keycloak.sync.admin-url=htp://not-a-url",
            "app.keycloak.sync.realm=iri",
            "app.keycloak.sync.client-id=backend-service",
            "app.keycloak.sync.client-secret=secret")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** The same configuration with a well-formed URL must start, for the same reason as above. */
  @Test
  void shouldStart_WhenKeycloakAdminUrlIsValid() {
    keycloakSyncRunner
        .withPropertyValues(
            "app.keycloak.sync.enabled=true",
            "app.keycloak.sync.admin-url=https://keycloak.example.invalid",
            "app.keycloak.sync.realm=iri",
            "app.keycloak.sync.client-id=backend-service",
            "app.keycloak.sync.client-secret=secret")
        .run((context) -> assertThat(context).hasNotFailed());
  }

  /**
   * A zero TTL would build a Caffeine cache that expires every entry immediately, silently
   * restoring the per-request {@code syncUser} + permission-table query storm the cache exists to
   * bound (ADR-0174). It must fail the context, not degrade at run time.
   */
  @Test
  void shouldFail_WhenAuthoritiesCacheTtlIsZero() {
    authoritiesCacheRunner
        .withPropertyValues("app.security.authorities-cache.ttl=0s")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** A negative TTL is rejected for the same reason a zero one is. */
  @Test
  void shouldFail_WhenAuthoritiesCacheTtlIsNegative() {
    authoritiesCacheRunner
        .withPropertyValues("app.security.authorities-cache.ttl=-1m")
        .run((context) -> assertThat(context).hasFailed());
  }

  /**
   * The TTL is the window in which a revoked role, permission, approval or membership stays
   * effective on an already-issued token. Anything past {@link AuthoritiesCacheProperties#MAX_TTL}
   * widens that window beyond what the access model assumes, so a mistyped value must not start.
   */
  @Test
  void shouldFail_WhenAuthoritiesCacheTtlExceedsCeiling() {
    authoritiesCacheRunner
        .withPropertyValues("app.security.authorities-cache.ttl=16m")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** The ceiling itself is allowed — the constraint is "at most", not "below". */
  @Test
  void shouldBind_WhenAuthoritiesCacheTtlIsExactlyTheCeiling() {
    authoritiesCacheRunner
        .withPropertyValues("app.security.authorities-cache.ttl=15m")
        .run(
            (context) ->
                assertThat(context.getBean(AuthoritiesCacheProperties.class).ttl())
                    .isEqualTo(AuthoritiesCacheProperties.MAX_TTL));
  }

  /**
   * With nothing configured the shipped default is five minutes (ADR-0174). Asserted because every
   * environment that does not set the variable — dev, test, e2e, and production until an operator
   * overrides it — runs on exactly this value.
   */
  @Test
  void shouldDefaultToFiveMinutes_WhenAuthoritiesCacheTtlOmitted() {
    authoritiesCacheRunner.run(
        (context) ->
            assertThat(context.getBean(AuthoritiesCacheProperties.class).ttl())
                .isEqualTo(Duration.ofMinutes(5)));
  }

  // covers REQ-SEC-033 carve-out (APPSEC-10) — a zero export capacity would refuse every export
  @Test
  void shouldFail_WhenSubjectExportCapacityIsZero() {
    rateLimitRunner
        .withPropertyValues("app.rate-limit.subject.export.capacity=0")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** The shipped export budget is ten a minute (owner decision 2026-09-22). */
  @Test
  void shouldDefaultToTenPerMinute_WhenSubjectExportOmitted() {
    rateLimitRunner.run(
        (context) -> {
          RateLimitProperties.Export export =
              context.getBean(RateLimitProperties.class).subject().export();
          assertThat(export.capacity()).isEqualTo(10);
          assertThat(export.refillTokens()).isEqualTo(10);
          assertThat(export.refillPeriod()).isEqualTo(Duration.ofMinutes(1));
        });
  }

  // covers REQ-AUDIT-006 (BE-MOD-03) — P0D would put the cutoff at "now" and purge the whole trail
  @Test
  void shouldFail_WhenAuditRetentionMaxAgeIsZero() {
    auditRetentionRunner
        .withPropertyValues("app.audit.retention.max-age=P0D")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** A negative window puts the cutoff in the future — every row would be "older". */
  @Test
  void shouldFail_WhenAuditRetentionMaxAgeIsNegative() {
    auditRetentionRunner
        .withPropertyValues("app.audit.retention.max-age=-P1D")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** One day under the floor is refused; the floor itself is accepted (the twin below). */
  @Test
  void shouldFail_WhenAuditRetentionMaxAgeIsBelowTheFloor() {
    auditRetentionRunner
        .withPropertyValues("app.audit.retention.max-age=P29D")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** The floor itself is allowed — the constraint is "at least", not "above". */
  @Test
  void shouldBind_WhenAuditRetentionMaxAgeIsExactlyTheFloor() {
    auditRetentionRunner
        .withPropertyValues("app.audit.retention.max-age=P30D")
        .run(
            (context) ->
                assertThat(context.getBean(AuditRetentionProperties.class).maxAge())
                    .isEqualTo(Duration.ofDays(AuditRetentionProperties.MIN_MAX_AGE_DAYS)));
  }

  /** A zero interval would turn the daily sweep into a busy loop against the audit tables. */
  @Test
  void shouldFail_WhenAuditRetentionIntervalIsZero() {
    auditRetentionRunner
        .withPropertyValues("app.audit.retention.interval=PT0S")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** With nothing set, the shipped two-year window and daily pace apply. */
  @Test
  void shouldDefault_WhenAuditRetentionOmitted() {
    auditRetentionRunner.run(
        (context) -> {
          AuditRetentionProperties properties = context.getBean(AuditRetentionProperties.class);
          assertThat(properties.enabled()).isTrue();
          assertThat(properties.maxAge()).isEqualTo(Duration.ofDays(730));
          assertThat(properties.interval()).isEqualTo(Duration.ofHours(24));
        });
  }

  // covers REQ-SEC-057 (BE-MOD-03) — "not zero on purpose" is a startup check now
  @Test
  void shouldFail_WhenRejectedRetentionMaxAgeIsZero() {
    rejectedRetentionRunner
        .withPropertyValues("app.registrations.rejected-retention.max-age=P0D")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** The one-day floor itself binds, and the default stays ninety days. */
  @Test
  void shouldBind_WhenRejectedRetentionMaxAgeIsOneDay() {
    rejectedRetentionRunner
        .withPropertyValues("app.registrations.rejected-retention.max-age=P1D")
        .run(
            (context) ->
                assertThat(context.getBean(RejectedRegistrationRetentionProperties.class).maxAge())
                    .isEqualTo(Duration.ofDays(1)));
    rejectedRetentionRunner.run(
        (context) ->
            assertThat(context.getBean(RejectedRegistrationRetentionProperties.class).maxAge())
                .isEqualTo(Duration.ofDays(90)));
  }

  // covers REQ-NOTIF-009 (BE-MOD-03) — both windows carry the floor
  @Test
  void shouldFail_WhenNotificationReadMaxAgeIsZero() {
    notificationRetentionRunner
        .withPropertyValues("app.notifications.retention.max-age=P0D")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** The unread window is floored on its own, not only through the read one. */
  @Test
  void shouldFail_WhenNotificationUnreadMaxAgeIsNegative() {
    notificationRetentionRunner
        .withPropertyValues(
            "app.notifications.retention.max-age=P1D",
            "app.notifications.retention.unread-max-age=-P1D")
        .run((context) -> assertThat(context).hasFailed());
  }

  // covers REQ-NOTIF-009 — an unread notification is never reaped sooner than a read one
  @Test
  void shouldFail_WhenNotificationUnreadWindowIsShorterThanReadWindow() {
    notificationRetentionRunner
        .withPropertyValues(
            "app.notifications.retention.max-age=P90D",
            "app.notifications.retention.unread-max-age=P30D")
        .run((context) -> assertThat(context).hasFailed());
  }

  /** Equal windows are allowed ("never sooner"), and the defaults are 90 / 180 days. */
  @Test
  void shouldBind_WhenNotificationWindowsAreEqualOrDefault() {
    notificationRetentionRunner
        .withPropertyValues(
            "app.notifications.retention.max-age=P1D",
            "app.notifications.retention.unread-max-age=P1D")
        .run((context) -> assertThat(context).hasNotFailed());
    notificationRetentionRunner.run(
        (context) -> {
          NotificationRetentionProperties properties =
              context.getBean(NotificationRetentionProperties.class);
          assertThat(properties.maxAge()).isEqualTo(Duration.ofDays(90));
          assertThat(properties.unreadMaxAge()).isEqualTo(Duration.ofDays(180));
        });
  }
}
