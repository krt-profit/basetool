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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Tests the release-bound Android version policy (REQ-API-020): the committed default, the
 * emergency override, startup validation, and that a stale host value under the retired variable
 * names cannot reach the floor.
 *
 * <p>Binds against the committed {@code application.yml} and a simulated container environment, the
 * way the backend binds them at startup.
 */
class AndroidClientPropertiesTest {

  /** The release page the committed configuration announces. */
  private static final String RELEASES =
      "https://github.com/krt-profit/basetool-android/releases/latest";

  /** The floor and newest build committed with this release. */
  private static final int COMMITTED_CODE = 17;

  /** Enables the record under test. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AndroidClientProperties.class)
  static class PolicyConfig {}

  /**
   * The pre-release shape of the policy, bound only to show that the simulated environment does
   * reach a property through relaxed binding.
   *
   * @param minimumVersionCode the floor under the retired {@code app.android} prefix
   */
  @ConfigurationProperties(prefix = "app.android")
  record RetiredShape(@DefaultValue("0") Integer minimumVersionCode) {}

  /** Enables the retired shape. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RetiredShape.class)
  static class RetiredShapeConfig {}

  /**
   * Builds a runner over the committed {@code application.yml} and the given container environment.
   *
   * @param environment the variables the container would carry
   * @return the runner
   */
  private static ApplicationContextRunner runner(Map<String, Object> environment) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(PolicyConfig.class, RetiredShapeConfig.class)
        .withInitializer(context -> install(context, environment));
  }

  /**
   * Replaces the context's system environment with {@code environment} and adds the committed
   * {@code application.yml} below it.
   *
   * @param context the context under construction
   * @param environment the simulated container environment
   */
  private static void install(
      ConfigurableApplicationContext context, Map<String, Object> environment) {
    MutablePropertySources sources = context.getEnvironment().getPropertySources();
    sources.replace(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
    try {
      new YamlPropertySourceLoader()
          .load("application.yml", new ClassPathResource("application.yml"))
          .forEach(sources::addLast);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  @DisplayName("the floor committed with the release is in force when the host sets nothing")
  void theCommittedReleaseDefaultIsInForce() {
    runner(Map.of())
        .run(
            context -> {
              AndroidClientProperties policy = context.getBean(AndroidClientProperties.class);
              assertThat(policy.minimumVersionCode()).isEqualTo(COMMITTED_CODE);
              assertThat(policy.latestVersionCode()).isEqualTo(COMMITTED_CODE);
              assertThat(policy.releasesUrl()).isEqualTo(RELEASES);
              assertThat(policy.minimumVersionCodeOverridden()).isFalse();
              assertThat(policy.latestVersionCodeOverridden()).isFalse();
              assertThat(policy.releasesUrlOverridden()).isFalse();
            });
  }

  @Test
  @DisplayName("a stale host value under the retired names cannot pin the floor")
  void aStaleHostValueUnderTheRetiredNamesCannotPinTheFloor() {
    runner(staleEnvironment())
        .run(
            context -> {
              AndroidClientProperties policy = context.getBean(AndroidClientProperties.class);
              assertThat(policy.minimumVersionCode()).isEqualTo(COMMITTED_CODE);
              assertThat(policy.latestVersionCode()).isEqualTo(COMMITTED_CODE);
              assertThat(policy.releasesUrl()).isEqualTo(RELEASES);
            });
  }

  @Test
  @DisplayName("control: the simulated environment does bind the stale value to the retired shape")
  void theSimulatedEnvironmentReachesTheRetiredShape() {
    runner(staleEnvironment())
        .run(
            context ->
                assertThat(context.getBean(RetiredShape.class).minimumVersionCode())
                    .as(
                        "relaxed binding maps APP_ANDROID_MINIMUM_VERSION_CODE onto"
                            + " app.android.minimum-version-code; without this the stale-value"
                            + " test above could not fail")
                    .isEqualTo(99));
  }

  @Test
  @DisplayName("the emergency override replaces the release default, field by field")
  void theOverrideReplacesTheReleaseDefault() {
    Map<String, Object> environment = new LinkedHashMap<>();
    environment.put("APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE", "18");
    environment.put("APP_ANDROID_RELEASES_URL_OVERRIDE", "https://example.org/releases");
    runner(environment)
        .run(
            context -> {
              AndroidClientProperties policy = context.getBean(AndroidClientProperties.class);
              assertThat(policy.minimumVersionCode()).isEqualTo(18);
              assertThat(policy.minimumVersionCodeOverridden()).isTrue();
              assertThat(policy.latestVersionCode()).isEqualTo(COMMITTED_CODE);
              assertThat(policy.latestVersionCodeOverridden()).isFalse();
              assertThat(policy.releasesUrl()).isEqualTo("https://example.org/releases");
              assertThat(policy.releasesUrlOverridden()).isTrue();
            });
  }

  @Test
  @DisplayName("an override may lower the floor to zero")
  void anOverrideMayLowerTheFloorToZero() {
    runner(Map.of("APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE", "0"))
        .run(
            context ->
                assertThat(context.getBean(AndroidClientProperties.class).minimumVersionCode())
                    .isZero());
  }

  @Test
  @DisplayName("an empty override, as the rendered environment carries it, keeps the default")
  void anEmptyOverrideKeepsTheReleaseDefault() {
    Map<String, Object> environment = new LinkedHashMap<>();
    environment.put("APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE", "");
    environment.put("APP_ANDROID_LATEST_VERSION_CODE_OVERRIDE", "");
    environment.put("APP_ANDROID_RELEASES_URL_OVERRIDE", "");
    runner(environment)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              AndroidClientProperties policy = context.getBean(AndroidClientProperties.class);
              assertThat(policy.minimumVersionCode()).isEqualTo(COMMITTED_CODE);
              assertThat(policy.latestVersionCode()).isEqualTo(COMMITTED_CODE);
              assertThat(policy.releasesUrl()).isEqualTo(RELEASES);
              assertThat(policy.minimumVersionCodeOverridden()).isFalse();
              assertThat(policy.releasesUrlOverridden()).isFalse();
            });
  }

  /**
   * An invalid override stops the backend from starting rather than serving a wrong policy.
   *
   * @param variable the override variable
   * @param value its invalid value
   */
  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE, -1",
    "APP_ANDROID_MINIMUM_VERSION_CODE_OVERRIDE, seventeen",
    "APP_ANDROID_LATEST_VERSION_CODE_OVERRIDE, -3",
    "APP_ANDROID_RELEASES_URL_OVERRIDE, http://example.org/releases",
    "APP_ANDROID_RELEASES_URL_OVERRIDE, not a url"
  })
  @DisplayName("an invalid override fails startup")
  void anInvalidOverrideFailsStartup(String variable, String value) {
    runner(Map.of(variable, value)).run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("without the committed block the code-level floor is zero")
  void withoutCommittedConfigurationTheFloorIsZero() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(PolicyConfig.class)
        .run(
            context -> {
              AndroidClientProperties policy = context.getBean(AndroidClientProperties.class);
              assertThat(policy.minimumVersionCode()).isZero();
              assertThat(policy.latestVersionCode()).isZero();
              assertThat(policy.releasesUrl()).isEqualTo(RELEASES);
            });
  }

  /**
   * The values production's {@code .env} may still hold under the variable names the policy no
   * longer reads.
   *
   * @return the stale environment
   */
  private static Map<String, Object> staleEnvironment() {
    Map<String, Object> environment = new LinkedHashMap<>();
    environment.put("APP_ANDROID_MINIMUM_VERSION_CODE", "99");
    environment.put("APP_ANDROID_LATEST_VERSION_CODE", "99");
    environment.put("APP_ANDROID_RELEASES_URL", "https://stale.example/releases");
    return environment;
  }
}
