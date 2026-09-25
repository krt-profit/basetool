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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * Verifies that an unreadable session value signs the member out instead of failing the request,
 * and that each failure shape is reported with enough detail to fix it but without the member's
 * data.
 */
class FaultTolerantSessionSerializerTest {

  /** A delegate that fails every read, standing in for any cause of an unreadable value. */
  private static final RedisSerializer<Object> ALWAYS_FAILS =
      new RedisSerializer<>() {
        @Override
        public byte[] serialize(Object value) {
          return new byte[] {1, 2, 3};
        }

        @Override
        public Object deserialize(byte[] bytes) {
          throw new SerializationException("cannot read", new IllegalStateException("root cause"));
        }
      };

  /** Registry the drop counter binds to; asserted on directly in the metric case. */
  private final MeterRegistry registry = new SimpleMeterRegistry();

  /**
   * The registry provider the production wiring passes in. Taken from a real bean factory rather
   * than hand-rolled, so the lazy resolution the production code relies on is the one under test.
   */
  private final ObjectProvider<MeterRegistry> registryProvider = registryProvider();

  /**
   * Builds an {@link ObjectProvider} over this test's registry.
   *
   * @return a provider backed by a real bean factory holding {@link #registry}.
   */
  private ObjectProvider<MeterRegistry> registryProvider() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("meterRegistry", registry);
    return beanFactory.getBeanProvider(MeterRegistry.class);
  }

  /**
   * Wraps a delegate the way {@code RedisSessionConfig} does.
   *
   * @param delegate the serializer under the wrapper.
   * @return the wrapper, bound to this test's registry.
   */
  private FaultTolerantSessionSerializer wrap(RedisSerializer<Object> delegate) {
    return new FaultTolerantSessionSerializer(delegate, registryProvider);
  }

  /**
   * The serializer the application really configures, unwrapped.
   *
   * @return a serializer over {@code RedisSessionConfig}'s own {@code JsonMapper}.
   */
  private static RedisSerializer<Object> productionSerializer() {
    return new GenericJacksonJsonRedisSerializer(
        RedisSessionConfig.buildSessionJsonMapper(
            FaultTolerantSessionSerializerTest.class.getClassLoader()));
  }

  /**
   * Reads bytes through the wrapper and asserts the read was refused.
   *
   * @param delegate the serializer under the wrapper.
   * @param bytes the stored payload.
   * @return the marker describing the failure.
   */
  private UnreadableSessionValue drop(RedisSerializer<Object> delegate, byte[] bytes) {
    return assertInstanceOf(UnreadableSessionValue.class, wrap(delegate).deserialize(bytes));
  }

  @Test
  void anUnreadableValueBecomesAMarkerRatherThanAnException() {
    assertInstanceOf(UnreadableSessionValue.class, wrap(ALWAYS_FAILS).deserialize(new byte[] {9}));
  }

  @Test
  void aWriteFailureIsNotSwallowed() {
    RedisSerializer<Object> serializer =
        wrap(
            new RedisSerializer<>() {
              @Override
              public byte[] serialize(Object value) {
                throw new SerializationException("cannot write");
              }

              @Override
              public Object deserialize(byte[] bytes) {
                return null;
              }
            });

    assertThrows(SerializationException.class, () -> serializer.serialize("anything"));
  }

  @Test
  void aReadableValueIsPassedThroughUntouched() {
    RedisSerializer<Object> serializer = wrap(productionSerializer());

    assertEquals(
        1_788_334_209_954L, serializer.deserialize(serializer.serialize(1_788_334_209_954L)));
  }

  @Test
  void writesStillGoStraightToTheDelegate() {
    assertArrayEquals(new byte[] {1, 2, 3}, wrap(ALWAYS_FAILS).serialize("anything"));
  }

  @Test
  void theProductionPayloadShapeIsSurvivableAndNamesItsShape() {
    RedisSerializer<Object> raw = productionSerializer();
    byte[] withoutTypeId = "{\"token\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    assertThrows(SerializationException.class, () -> raw.deserialize(withoutTypeId));

    UnreadableSessionValue marker = drop(raw, withoutTypeId);

    assertEquals("InvalidTypeIdException", marker.cause());
    assertEquals(UnreadableSessionValue.TYPE_ID_ABSENT, marker.typeId());
    assertEquals("java.lang.Object", marker.baseType());
  }

  @Test
  void aStaleNestedClassNameIsNamedInFull() {
    RedisSerializer<Object> raw = productionSerializer();
    byte[] stale =
        ("{\"@class\":\"java.util.LinkedHashMap\","
                + "\"k\":{\"@class\":\"de.greluc.krt.Vanished\",\"a\":1}}")
            .getBytes(StandardCharsets.UTF_8);

    UnreadableSessionValue marker = drop(raw, stale);

    assertEquals("de.greluc.krt.Vanished", marker.typeId());
  }

  @Test
  void aTypeIdThatIsNotAClassNameIsNotLoggedVerbatim() {
    RedisSerializer<Object> raw = productionSerializer();
    byte[] bareArray = raw.serialize(List.of("member@example.invalid", "second"));

    UnreadableSessionValue marker = drop(raw, bareArray);

    assertEquals(UnreadableSessionValue.TYPE_ID_NOT_A_CLASS_NAME, marker.typeId());
  }

  @Test
  void theAttributeThatCausedTheOutageIsSurvivable() {
    RedisSerializer<Object> raw = productionSerializer();
    byte[] stored =
        raw.serialize(
            new org.springframework.security.authentication.InsufficientAuthenticationException(
                "authentication failed"));
    assertThrows(SerializationException.class, () -> raw.deserialize(stored));

    UnreadableSessionValue marker = drop(raw, stored);

    assertEquals("IllegalArgumentException", marker.cause());
  }

  @Test
  void aNonJacksonFailureStillYieldsAMarkerWithoutThrowing() {
    UnreadableSessionValue marker = drop(ALWAYS_FAILS, new byte[] {9});

    assertEquals("IllegalStateException", marker.cause());
    assertEquals(UnreadableSessionValue.NOT_APPLICABLE, marker.typeId());
    assertEquals(UnreadableSessionValue.NOT_APPLICABLE, marker.baseType());
  }

  @Test
  void aCauseCycleDoesNotHangTheRequestThread() {
    Exception first = new IllegalStateException("first");
    Exception second = new IllegalStateException("second");
    first.initCause(second);
    second.initCause(first);
    RedisSerializer<Object> cyclic =
        new RedisSerializer<>() {
          @Override
          public byte[] serialize(Object value) {
            return new byte[0];
          }

          @Override
          public Object deserialize(byte[] bytes) {
            throw new SerializationException("cannot read", first);
          }
        };

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertInstanceOf(
                UnreadableSessionValue.class, wrap(cyclic).deserialize(new byte[] {9})));
  }

  @Test
  void everyDropIsCountedUnderABoundedCauseTag() {
    wrap(ALWAYS_FAILS).deserialize(new byte[] {9});
    wrap(ALWAYS_FAILS).deserialize(new byte[] {9});

    assertEquals(
        2.0,
        registry
            .counter(MetricNames.SESSION_VALUE_DROPPED, MetricNames.TAG_CAUSE, "other")
            .count());
  }

  @Test
  void anUnknownCauseFoldsIntoTheOtherBucketRatherThanBecomingALabel() {
    drop(ALWAYS_FAILS, new byte[] {9});

    assertEquals(
        List.of("other"),
        registry.find(MetricNames.SESSION_VALUE_DROPPED).counters().stream()
            .map(counter -> counter.getId().getTag(MetricNames.TAG_CAUSE))
            .toList());
  }
}
