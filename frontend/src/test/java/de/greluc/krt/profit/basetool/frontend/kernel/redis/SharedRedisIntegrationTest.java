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

package de.greluc.krt.profit.basetool.frontend.kernel.redis;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * With the shared Redis configured beside the session store, Spring Boot still auto-configures the
 * session store's connection and its template, and every write lands in the store it was meant for
 * (ADR-0388).
 */
@Testcontainers
class SharedRedisIntegrationTest {

  @Container
  static final GenericContainer<?> SESSION_STORE =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS)).withExposedPorts(6379);

  @Container
  static final GenericContainer<?> SHARED =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS)).withExposedPorts(6379);

  private static final String SESSION_KEY = "basetool:session:sessions:probe";

  private static final String HANDOFF_KEY = "ingest:handoff:sub:probe";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DataRedisAutoConfiguration.class, ValidationAutoConfiguration.class))
          .withUserConfiguration(SharedRedisConfig.class, Properties.class)
          .withPropertyValues(
              "spring.data.redis.host=" + SESSION_STORE.getHost(),
              "spring.data.redis.port=" + SESSION_STORE.getMappedPort(6379),
              "spring.data.redis.timeout=2s",
              "app.shared-redis.host=" + SHARED.getHost(),
              "app.shared-redis.port=" + SHARED.getMappedPort(6379));

  @BeforeEach
  void setUp() {
    for (GenericContainer<?> redis : List.of(SESSION_STORE, SHARED)) {
      LettuceConnectionFactory admin = direct(redis);
      try (RedisConnection connection = admin.getConnection()) {
        connection.serverCommands().flushAll();
      } finally {
        admin.destroy();
      }
    }
  }

  @Test
  void theSessionStoreKeepsTheOnlyAutoConfiguredConnection() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(RedisConnectionFactory.class);
      assertThat(context).hasSingleBean(StringRedisTemplate.class);
      assertThat(context).hasSingleBean(SharedRedis.class);
      assertThat(context.getBean(SharedRedis.class).connectionFactory())
          .isNotSameAs(context.getBean(RedisConnectionFactory.class));
    });
  }

  @Test
  void eachTemplateWritesIntoItsOwnStore() {
    runner.run(context -> {
      context.getBean(StringRedisTemplate.class).opsForValue().set(SESSION_KEY, "session");
      context.getBean(SharedRedis.class).template().opsForValue().set(HANDOFF_KEY, "handoff");
    });

    assertThat(read(SESSION_STORE, SESSION_KEY)).isEqualTo("session");
    assertThat(read(SESSION_STORE, HANDOFF_KEY)).isNull();
    assertThat(read(SHARED, HANDOFF_KEY)).isEqualTo("handoff");
    assertThat(read(SHARED, SESSION_KEY)).isNull();
  }

  @Test
  void aSharedRedisWithoutAHostFailsTheStart() {
    runner
        .withPropertyValues("app.shared-redis.host=")
        .run(context ->
            assertThat(context)
                .getFailure()
                .hasStackTraceContaining("app.shared-redis")
                .hasStackTraceContaining("host"));
  }

  /**
   * Reads one key straight from a container, past the application context.
   *
   * @param redis the container
   * @param key the key
   * @return the value, or {@code null} when the key is absent
   */
  private static String read(@NotNull GenericContainer<?> redis, @NotNull String key) {
    LettuceConnectionFactory factory = direct(redis);
    try {
      return new StringRedisTemplate(factory).opsForValue().get(key);
    } finally {
      factory.destroy();
    }
  }

  /**
   * Starts a factory straight to a container.
   *
   * @param redis the container
   * @return the started factory
   */
  private static @NotNull LettuceConnectionFactory direct(@NotNull GenericContainer<?> redis) {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    return factory;
  }

  /** Binds the shared Redis's properties, as the application's properties scan does. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SharedRedisProperties.class)
  static class Properties {}
}
