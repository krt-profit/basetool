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
 * A session hash that exists and is missing a required field, against a real Redis and the real
 * {@link RedisIndexedSessionRepository} — the production fault of REQ-SEC-063, reproduced from the
 * write that actually causes it rather than from a hand-built map.
 *
 * <p><strong>Why the reproduction is a delete-then-commit and not a fixture.</strong> The
 * interesting claim is not "a map without {@code creationTime} makes the mapper throw" — that is
 * one line of upstream code. It is that <em>Spring Session itself produces such a hash under
 * ordinary operation</em>: {@code RedisSession#saveDelta} issues a plain {@code HSET} of the
 * changed fields only, and {@code creationTime} is in that delta solely {@code if (isNew)}. So a
 * request that read its session before the hash vanished — a Redis restart, an AOF truncation, a
 * purge run against live traffic — re-creates the key holding {@code lastAccessedTime} alone when
 * it commits, and puts the full session TTL back on it. {@link
 * #aDeltaWriteAfterTheHashVanishesReCreatesItHalfWritten()} is that sequence, and it is the half
 * the production investigation of 2026-09-16 could only infer from the library sources.
 *
 * <p>Before the fix this state answered {@code IllegalStateException: creationTime key must not be
 * null} out of {@code SessionRepositoryFilter} — an HTTP 500 on <em>every</em> request carrying
 * that cookie, for up to the 720-hour authenticated window, because nothing on the read path
 * catches it and nothing clears the cookie. 286 of them landed on 2026-09-14 and no alert could see
 * them.
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

    // Assembled exactly as RedisSessionConfig assembles it in production.
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
    // The producer, reproduced: read a live session, lose its hash underneath the request, then let
    // the request commit. saveDelta HSETs only what changed, so the key comes back holding one
    // field — and carries a TTL again, which is why the state is durable rather than a blip.
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
    // The fix. Before it, findById threw IllegalStateException out of the repository and every
    // request that browser made answered 500 until the cookie was deleted by hand.
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
    // ADR-0157's boundary, pinned: the mapper reports and gives up, it never writes. Repairing here
    // would put a Redis write on the session READ path — the subsystem that took the whole
    // application down twice inside two releases. The orphan is left to expire with its TTL.
    String id = halfWrittenSession();

    repository.findById(id);

    assertThat(raw.opsForHash().keys(hashKey(id)))
        .as("the hash is untouched — no repair, no delete")
        .containsExactly(LAST_ACCESSED_TIME);
  }

  @Test
  void aSessionThatKeepsItsRequiredFieldsIsUnaffected() {
    // The guard must not change the ordinary read. An unmappable hash is the exception; every
    // healthy session still loads, and nothing is counted.
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
