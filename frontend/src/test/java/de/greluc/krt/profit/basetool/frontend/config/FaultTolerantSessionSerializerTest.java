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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * An unreadable session value must sign a member out, not take the application down.
 *
 * <p><strong>What this pins.</strong> On 2026-09-02 a session attribute in Redis could not be
 * deserialized, and because nothing on Spring Session's read path catches that, the exception
 * escaped the session filter and became an HTTP 500 on every request carrying a session cookie —
 * the entire application, behind the login, for everybody. The last case below is the real
 * production payload: a JSON object with no {@code @class} type id, read back through the very
 * mapper the application configures.
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

  @Test
  void anUnreadableValueBecomesAbsentRatherThanAnException() {
    // `null` is what Spring Session already means by "this attribute is not set", so the session
    // still loads — with its timestamps intact and its attributes stripped. That is a signed-out
    // member, which is recoverable by logging in; a 500 on every request is not recoverable at all.
    RedisSerializer<Object> serializer = new FaultTolerantSessionSerializer(ALWAYS_FAILS);

    assertNull(serializer.deserialize(new byte[] {9}));
  }

  @Test
  void aWriteFailureIsNotSwallowed() {
    // Deliberately asymmetric. A session that cannot be written must fail loudly: silently
    // accepting the write would hand the member a session that forgets everything it was told,
    // which is harder to diagnose than an error and corrupts nothing visibly while doing it.
    RedisSerializer<Object> serializer =
        new FaultTolerantSessionSerializer(
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
    RedisSerializer<Object> serializer =
        new FaultTolerantSessionSerializer(
            new GenericJacksonJsonRedisSerializer(
                RedisSessionConfig.buildSessionJsonMapper(
                    FaultTolerantSessionSerializerTest.class.getClassLoader())));

    assertEquals(
        1_788_334_209_954L, serializer.deserialize(serializer.serialize(1_788_334_209_954L)));
  }

  @Test
  void writesStillGoStraightToTheDelegate() {
    RedisSerializer<Object> serializer = new FaultTolerantSessionSerializer(ALWAYS_FAILS);

    assertArrayEquals(new byte[] {1, 2, 3}, serializer.serialize("anything"));
  }

  @Test
  void theProductionPayloadShapeIsSurvivable() {
    // The exact shape from the incident: a JSON object with no `@class`. Read as Object under the
    // NON_FINAL default typing that SecurityJacksonModules activates, Jackson demands the type id
    // and throws `missing type id property '@class'`. Unwrapped, that is the 500. Wrapped, it is a
    // stripped attribute.
    RedisSerializer<Object> raw =
        new GenericJacksonJsonRedisSerializer(
            RedisSessionConfig.buildSessionJsonMapper(
                FaultTolerantSessionSerializerTest.class.getClassLoader()));
    byte[] withoutTypeId = "{\"token\":\"x\"}".getBytes(StandardCharsets.UTF_8);

    assertThrows(SerializationException.class, () -> raw.deserialize(withoutTypeId));
    assertNull(new FaultTolerantSessionSerializer(raw).deserialize(withoutTypeId));
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
    // FAIL to arm it.
    RedisSerializer<Object> raw =
        new GenericJacksonJsonRedisSerializer(
            RedisSessionConfig.buildSessionJsonMapper(
                FaultTolerantSessionSerializerTest.class.getClassLoader()));
    byte[] stored =
        raw.serialize(
            new org.springframework.security.authentication.InsufficientAuthenticationException(
                "authentication failed"));

    assertThrows(SerializationException.class, () -> raw.deserialize(stored));
    assertNull(new FaultTolerantSessionSerializer(raw).deserialize(stored));
  }
}
