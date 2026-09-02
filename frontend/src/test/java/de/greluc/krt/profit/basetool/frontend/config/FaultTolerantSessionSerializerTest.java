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
 * An unreadable session value must sign a member out, not take the application down — and must say
 * enough about itself to be fixed.
 *
 * <p><strong>What this pins.</strong> On 2026-09-02 a session attribute in Redis could not be
 * deserialized, and because nothing on Spring Session's read path catches that, the exception
 * escaped the session filter and became an HTTP 500 on every request carrying a session cookie —
 * the entire application, behind the login, for everybody.
 *
 * <p>The diagnostics cases pin the follow-up. Once the fault was survivable it stopped being
 * legible: three hours of that day's export are 496 identical WARN lines naming only {@code
 * InvalidTypeIdException}, with no attribute, no type id and no metric. The assertions below fix
 * the meaning of each of the three failure shapes, and one of them exists specifically to keep a
 * member's own data out of a log line.
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
    // The marker, not `null`: `null` is what Spring Session already means by "this attribute is not
    // set", and the application tombstones attributes on every role re-check and terms re-check, so
    // null could not tell poison from housekeeping. SessionAttributeDiagnosticMapper turns the
    // marker back into the null the rest of the stack expects, after naming the attribute.
    assertInstanceOf(UnreadableSessionValue.class, wrap(ALWAYS_FAILS).deserialize(new byte[] {9}));
  }

  @Test
  void aWriteFailureIsNotSwallowed() {
    // Deliberately asymmetric. A session that cannot be written must fail loudly: silently
    // accepting the write would hand the member a session that forgets everything it was told,
    // which is harder to diagnose than an error and corrupts nothing visibly while doing it.
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
    // The exact shape from the incident: a JSON object with no `@class`. Read as Object under the
    // NON_FINAL default typing that SecurityJacksonModules activates, Jackson demands the type id
    // and throws `missing type id property '@class'`. Unwrapped, that is the 500.
    RedisSerializer<Object> raw = productionSerializer();
    byte[] withoutTypeId = "{\"token\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    assertThrows(SerializationException.class, () -> raw.deserialize(withoutTypeId));

    UnreadableSessionValue marker = drop(raw, withoutTypeId);

    // `absent` is the single most informative value this field takes: it says the value was written
    // by a FINAL runtime type — a record, a List.of(…), any final class — which NON_FINAL typing
    // writes without a type id and then refuses to read back.
    assertEquals("InvalidTypeIdException", marker.cause());
    assertEquals(UnreadableSessionValue.TYPE_ID_ABSENT, marker.typeId());
    assertEquals("java.lang.Object", marker.baseType());
  }

  @Test
  void aStaleNestedClassNameIsNamedInFull() {
    // The upgrade-shaped failure: a session written by an older build carries a @class that this
    // build can no longer resolve. Sessions live up to 720 hours and outlive several deploys, so
    // this is the shape a rename produces — and the type id IS the answer, which is exactly what
    // the incident's log line could not say.
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
    // Measured, not defensive. A `List.of(…)` stored as a session attribute is written as a bare
    // JSON array — no @class, because the JDK's immutable list is final — and the reader then takes
    // ELEMENT ZERO for the type id. Jackson reports `Could not resolve type id 'sensitive-value'`,
    // where that string is the member's own data. Logging it verbatim would put session payload in
    // a log line, which REQ-OBS-004 forbids outright.
    RedisSerializer<Object> raw = productionSerializer();
    byte[] bareArray = raw.serialize(List.of("member@example.invalid", "second"));

    UnreadableSessionValue marker = drop(raw, bareArray);

    assertEquals(UnreadableSessionValue.TYPE_ID_NOT_A_CLASS_NAME, marker.typeId());
  }

  @Test
  void theAttributeThatCausedTheOutageIsSurvivable() {
    // The culprit, found by surveying the live session hashes: SPRING_SECURITY_LAST_EXCEPTION.
    // Spring Security parks the last failed authentication in the session, and this value writes
    // cleanly WITH its @class and then cannot be read back — reconstruction dies on
    // `IllegalArgumentException: authenticationRequest cannot be null`, a field the serialized form
    // never carried. Reading a session deserializes every field, so this one poisons the whole
    // session: from the next request on, that member gets a 500 on everything.
    //
    // It is not a regression. The same probe fails identically on v1.6.12, so the trap has been
    // latent for as long as sessions have been JSON — a release only has to make an authentication
    // FAIL to arm it. PR #1755 stopped the write; this pins that the read stays survivable.
    RedisSerializer<Object> raw = productionSerializer();
    byte[] stored =
        raw.serialize(
            new org.springframework.security.authentication.InsufficientAuthenticationException(
                "authentication failed"));
    assertThrows(SerializationException.class, () -> raw.deserialize(stored));

    UnreadableSessionValue marker = drop(raw, stored);

    // A third distinct bucket, and the reason the triage key in the class Javadoc has three lines
    // rather than two: this failure never reaches Jackson's type resolution at all.
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
    // The cause walk this class shipped with guarded only against self-reference, so a two-element
    // cycle spun forever — inside a catch block on the session read path, i.e. on every request.
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
    // 496 dropped values produced no number at all on 2026-09-02. This is that number.
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
    // A tag fed from an arbitrary exception class name is an unbounded label, and a novel failure
    // would then be a cardinality incident rather than a log line (REQ-OBS-006).
    drop(ALWAYS_FAILS, new byte[] {9});

    assertEquals(
        List.of("other"),
        registry.find(MetricNames.SESSION_VALUE_DROPPED).counters().stream()
            .map(counter -> counter.getId().getTag(MetricNames.TAG_CAUSE))
            .toList());
  }
}
