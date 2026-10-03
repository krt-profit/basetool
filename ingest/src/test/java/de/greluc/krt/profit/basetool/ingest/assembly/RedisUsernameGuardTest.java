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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests that production never reaches Redis as {@code default}, and that the shipped {@code
 * application.yml} takes the username from {@code REDIS_USERNAME}, which the compose file and the
 * Quadlet env template fill from {@code REDIS_INGEST_USERNAME} (REQ-SEC-068).
 */
class RedisUsernameGuardTest {

  @Test
  void prodWithoutAUsernameRefusesToStart() throws IOException {
    RedisUsernameGuard guard = new RedisUsernameGuard(shipped(Map.of(), "prod"));

    assertThatThrownBy(guard::afterPropertiesSet)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("REDIS_INGEST_USERNAME");
  }

  @Test
  void prodAsTheDefaultUserOrABlankOneRefusesToStart() throws IOException {
    assertThatThrownBy(
            () ->
                new RedisUsernameGuard(shipped(Map.of("REDIS_USERNAME", "default"), "prod"))
                    .afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new RedisUsernameGuard(shipped(Map.of("REDIS_USERNAME", "  "), "prod"))
                    .afterPropertiesSet())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void prodAsItsOwnUserStarts() throws IOException {
    MockEnvironment environment = shipped(Map.of("REDIS_USERNAME", "basetool-ingest"), "prod");

    assertThat(environment.getProperty(RedisUsernameGuard.USERNAME_PROPERTY))
        .isEqualTo("basetool-ingest");
    assertThatCode(() -> new RedisUsernameGuard(environment).afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  @Test
  void devAndTestMayUseTheSharedPassword() throws IOException {
    assertThatCode(() -> new RedisUsernameGuard(shipped(Map.of(), "dev")).afterPropertiesSet())
        .doesNotThrowAnyException();
    assertThatCode(() -> new RedisUsernameGuard(shipped(Map.of(), "test")).afterPropertiesSet())
        .doesNotThrowAnyException();
  }

  /**
   * Builds an environment from the shipped {@code application.yml} and the given process
   * environment.
   *
   * @param variables the environment variables
   * @param profiles the active profiles
   * @return the environment
   * @throws IOException when {@code application.yml} cannot be read
   */
  private static @NotNull MockEnvironment shipped(
      @NotNull Map<String, Object> variables, String @NotNull ... profiles) throws IOException {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    for (PropertySource<?> source :
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
    return environment;
  }
}
