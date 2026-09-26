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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.FlushMode;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies against a real Redis and {@link RedisIndexedSessionRepository} that a session hash
 * missing a required field, as produced by a delta write after the hash vanished, degrades to a
 * signed-out member rather than an HTTP 500 (REQ-SEC-063).
 */
@Testcontainers
class HalfWrittenSessionHashIntegrationTest {

  /** Spring Session's hash field holding the session's creation timestamp. */
  private static final String CREATION_TIME = "creationTime";

  /** Spring Session's hash field holding the session's last-access timestamp. */
  private static final String LAST_ACCESSED_TIME = "lastAccessedTime";

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(TestImages.REDIS)).withExposedPorts(6379);

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private LettuceConnectionFactory connectionFactory;
  private RedisIndexedSessionRepository repository;
  private StringRedisTemplate raw;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();

    RedisSerializer<Object> sessionSerializer =
        new FaultTolerantSessionSerializer(
            new GenericJacksonJsonRedisSerializer(
                RedisSessionConfig.buildSessionJsonMapper(
                    HalfWrittenSessionHashIntegrationTest.class.getClassLoader())),
            registryProvider());
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setKeySerializer(RedisSerializer.string());
    template.setHashKeySerializer(RedisSerializer.string());
    template.setDefaultSerializer(sessionSerializer);
    template.setConnectionFactory(connectionFactory);
    template.afterPropertiesSet();

    repository = new RedisIndexedSessionRepository(template);
    repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
    repository.setRedisKeyNamespace("basetool:session");
    repository.setFlushMode(FlushMode.IMMEDIATE);
    repository.setRedisSessionMapper(new SessionAttributeDiagnosticMapper(registryProvider()));

    raw = new StringRedisTemplate(connectionFactory);
    raw.afterPropertiesSet();
  }

  @AfterEach
  void tearDown() {
    connectionFactory.destroy();
  }

  /**
   * Builds an {@link ObjectProvider} over this test's registry, the way {@code RedisSessionConfig}
   * hands one to the mapper.
   *
   * @return a provider backed by a real bean factory holding {@link #registry}.
   */
  private ObjectProvider<MeterRegistry> registryProvider() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("meterRegistry", registry);
    return beanFactory.getBeanProvider(MeterRegistry.class);
  }

  /**
   * Reads the unmappable-hash counter for one missing key.
   *
   * @param missingKey the {@code missing_key} tag value to read.
   * @return the count so far, or {@code 0} while that series is unregistered.
   */
  private double unmappable(String missingKey) {
    var counter =
        registry.find("basetool.session.unmappable").tag("missing_key", missingKey).counter();
    return counter == null ? 0d : counter.count();
  }

  /**
   * Redis key of a session hash.
   *
   * @param sessionId the session's id.
   * @return the namespaced hash key.
   */
  private static String hashKey(String sessionId) {
    return "basetool:session:sessions:" + sessionId;
  }

  @Test
  void aDeltaWriteAfterTheHashVanishesReCreatesItHalfWritten() {
    RedisIndexedSessionRepository.RedisSession session = repository.createSession();
    repository.save(session);
    String id = session.getId();

    RedisIndexedSessionRepository.RedisSession inFlight = repository.findById(id);
    assertThat(inFlight).isNotNull();
    raw.delete(hashKey(id));

    inFlight.setLastAccessedTime(java.time.Instant.now());
    repository.save(inFlight);

    assertThat(raw.opsForHash().keys(hashKey(id)))
        .as("the delta write re-created the key with the changed field only")
        .contains(LAST_ACCESSED_TIME)
        .doesNotContain(CREATION_TIME);
    assertThat(raw.getExpire(hashKey(id)))
        .as("and gave it a TTL again, so it outlives the request that made it")
        .isGreaterThan(0L);
  }

  @Test
  void aHalfWrittenHashReadsAsNoSessionInsteadOfThrowing() {
    String id = halfWrittenSession();

    double before = unmappable(CREATION_TIME);
    var loaded = repository.findById(id);

    assertThat(loaded).as("the cookie names nothing usable, so there is no session").isNull();
    assertThat(unmappable(CREATION_TIME) - before)
        .as("and the give-up is counted, because a silent null would hide a format break")
        .isEqualTo(1d);
  }

  @Test
  void theHalfWrittenHashIsNotRepairedFromTheReadPath() {
    String id = halfWrittenSession();

    repository.findById(id);

    assertThat(raw.opsForHash().keys(hashKey(id)))
        .as("the hash is untouched — no repair, no delete")
        .containsExactly(LAST_ACCESSED_TIME);
  }

  @Test
  void aSessionThatKeepsItsRequiredFieldsIsUnaffected() {
    RedisIndexedSessionRepository.RedisSession session = repository.createSession();
    session.setAttribute("welcomeMessageShown", Boolean.TRUE);
    repository.save(session);

    var loaded = repository.findById(session.getId());

    assertThat(loaded).isNotNull();
    assertThat((Object) loaded.getAttribute("welcomeMessageShown")).isEqualTo(Boolean.TRUE);
    assertThat(unmappable(CREATION_TIME)).isZero();
  }

  /**
   * Creates a real session and leaves its hash holding {@code lastAccessedTime} alone — the exact
   * state {@link #aDeltaWriteAfterTheHashVanishesReCreatesItHalfWritten()} proves Spring Session
   * produces on its own.
   *
   * @return the id of the half-written session.
   */
  private String halfWrittenSession() {
    RedisIndexedSessionRepository.RedisSession session = repository.createSession();
    repository.save(session);
    String id = session.getId();
    raw.opsForHash().delete(hashKey(id), CREATION_TIME, "maxInactiveInterval");
    return id;
  }
}
