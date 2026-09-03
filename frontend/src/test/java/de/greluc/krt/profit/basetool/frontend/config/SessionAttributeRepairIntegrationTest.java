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
 * The session write path and the poison repair, against a real Redis and the real {@link
 * RedisIndexedSessionRepository} — the layer that had no test and where both 2026-09-03 alerts
 * actually lived.
 *
 * <p><strong>Why this exists beside {@code SessionSerializerRoundTripTest}.</strong> That test
 * round-trips values through the serializer <em>in isolation</em>, and it was green throughout the
 * whole incident: it proved the mix-in works, and could not say whether Spring Session's repository
 * writes the attribute through that serializer, nor what happens to a value already sitting in
 * Redis in the pre-fix shape. Both alerts turned on exactly those two questions, and answering them
 * cost a production investigation each time. This test answers them from CI instead.
 *
 * <p>The pre-fix bytes are pinned as a literal rather than produced by a stripped-down mapper: what
 * is in production's Redis is a fixed string written by a release that no longer exists, and a
 * fixture that re-derives it would drift with the code it is supposed to be independent of.
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
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private LettuceConnectionFactory connectionFactory;
  private RedisIndexedSessionRepository repository;
  private StringRedisTemplate raw;

  @BeforeEach
  void setUp() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    connectionFactory.start();

    // Assembled exactly as RedisSessionConfig assembles it in production: the fault-tolerant
    // wrapper over the configured mapper, string keys, and the diagnostic session mapper.
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
    repository.setRedisSessionMapper(new SessionAttributeDiagnosticMapper());

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
    // The half SessionSerializerRoundTripTest cannot see: not "the serializer can write @class" but
    // "Spring Session's repository actually writes the attribute through that serializer". If this
    // ever fails, values are being poisoned again at the source and no amount of repair will help.
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
    // The 2026-09-03 defect, pinned. Without the repair this second read drops again, and so does
    // every read after it for up to the 720-hour authenticated window (REQ-SEC-025) — which is what
    // kept SessionValueDropsSustained firing for hours after the write path had already been fixed.
    String id = poisonedSession();
    var poisoned = repository.findById(id);
    assertThat(poisoned).isNotNull();

    // Exactly what SessionAttributeRepairFilter does on the way out of the chain.
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
    // removeAttribute writes an EMPTY value rather than issuing an HDEL — Spring Session's ordinary
    // removal shape. Pinned because the repair's whole safety argument rests on that empty value
    // deserialising to null instead of becoming a second kind of unreadable byte string.
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
