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
import org.apache.tomcat.websocket.server.WsHttpSessionBindingListener;
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
 * Verifies against a real Redis and {@link RedisIndexedSessionRepository} that session attributes
 * are written through the configured serializer and that a stored value in the old, unreadable
 * shape is repaired.
 *
 * <p>The old-shape bytes are pinned as a literal.
 */
@Testcontainers
class SessionAttributeRepairIntegrationTest {

  /** The attribute name Tomcat uses — {@code Class#getCanonicalName} of the record it writes. */
  private static final String ATTRIBUTE = WsHttpSessionBindingListener.class.getCanonicalName();

  /** Spring Session's hash field for that attribute. */
  private static final String FIELD = "sessionAttr:" + ATTRIBUTE;

  /**
   * Exactly what Tomcat's record looked like on the wire before ADR-0154's forced type id: a JSON
   * object with no {@code @class}, which the reader then demands and cannot find.
   */
  private static final String PRE_FIX_BYTES = "{\"key\":\"a-session-id\"}";

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
                    SessionAttributeRepairIntegrationTest.class.getClassLoader())),
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
    SessionAttributeRepairQueue.clear();
  }

  @AfterEach
  void tearDown() {
    SessionAttributeRepairQueue.clear();
    connectionFactory.destroy();
  }

  /**
   * Builds an {@link ObjectProvider} over this test's registry, the way {@code RedisSessionConfig}
   * receives one.
   *
   * @return a provider backed by a real bean factory holding {@link #registry}.
   */
  private ObjectProvider<MeterRegistry> registryProvider() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("meterRegistry", registry);
    return beanFactory.getBeanProvider(MeterRegistry.class);
  }

  /**
   * Reads the drop counter.
   *
   * @return the number of values dropped so far, or {@code 0} while the counter is unregistered.
   */
  private double drops() {
    var counter = registry.find("basetool.session.value.dropped").counter();
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
  void theRepositoryWritesTheContainerRecordWithItsForcedTypeId() {
    RedisIndexedSessionRepository.RedisSession session = repository.createSession();
    session.setAttribute(ATTRIBUTE, new WsHttpSessionBindingListener("a-session-id"));
    repository.save(session);

    assertThat((String) raw.opsForHash().get(hashKey(session.getId()), FIELD))
        .as("the container record must reach Redis carrying its @class")
        .isEqualTo(
            "{\"@class\":\"org.apache.tomcat.websocket.server.WsHttpSessionBindingListener\","
                + "\"key\":\"a-session-id\"}");
  }

  @Test
  void aPreFixValueIsDroppedOnceAndQueuedForRepair() {
    String id = poisonedSession();

    double before = drops();
    var loaded = repository.findById(id);

    assertThat(loaded).as("the session survives — only the attribute is lost").isNotNull();
    assertThat((Object) loaded.getAttribute(ATTRIBUTE)).as("the value reads as not set").isNull();
    assertThat(drops() - before).as("the drop is counted exactly once").isEqualTo(1d);
    assertThat(SessionAttributeRepairQueue.drain())
        .as("the attribute name is handed to the repair filter")
        .containsExactly(ATTRIBUTE);
  }

  @Test
  void theRepairEndsTheDropInsteadOfLettingItRepeatForever() {
    String id = poisonedSession();
    var poisoned = repository.findById(id);
    assertThat(poisoned).isNotNull();

    for (String attribute : SessionAttributeRepairQueue.drain()) {
      poisoned.removeAttribute(attribute);
    }

    double afterRepair = drops();
    var reread = repository.findById(id);

    assertThat(reread).as("the session is still usable").isNotNull();
    assertThat(drops() - afterRepair).as("nothing is dropped any more").isZero();
    assertThat(SessionAttributeRepairQueue.drain())
        .as("and nothing is queued for repair any more")
        .isEmpty();
  }

  @Test
  void theRepairedFieldStillReadsBackAsAbsentRatherThanFailing() {
    String id = poisonedSession();
    var poisoned = repository.findById(id);
    assertThat(poisoned).isNotNull();
    poisoned.removeAttribute(ATTRIBUTE);

    assertThat((String) raw.opsForHash().get(hashKey(id), FIELD))
        .as("the poisoned JSON is gone")
        .isEmpty();
    var reread = repository.findById(id);
    assertThat(reread).isNotNull();
    assertThat((Object) reread.getAttribute(ATTRIBUTE)).isNull();
  }

  /**
   * Creates a real session and overwrites its container attribute with the pre-fix bytes, which is
   * the state every session written before ADR-0154 shipped is still in.
   *
   * @return the poisoned session's id.
   */
  private String poisonedSession() {
    RedisIndexedSessionRepository.RedisSession session = repository.createSession();
    session.setAttribute(ATTRIBUTE, new WsHttpSessionBindingListener("a-session-id"));
    repository.save(session);
    raw.opsForHash().put(hashKey(session.getId()), FIELD, PRE_FIX_BYTES);
    SessionAttributeRepairQueue.clear();
    return session.getId();
  }
}
